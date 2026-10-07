/* 
 *  Copyright (c) 2025 Eclipse Foundation
 * 
 *  This program and the accompanying materials are made available 
 *  under the terms of the MIT license which is available at
 *  https://opensource.org/license/mit.
 * 
 *  SPDX-License-Identifier: MIT
 *
 *  Portions of this file were generated with AI assistance.
 * 
 *  Contributors: 
 *     Frédéric Desbiens - Initial version.
 */
#include "cloud_config.h"
#include "mqtt_client.h"
#include "nx_api.h"
#include "screen.h"
#include "telemetry.h"
#include "wwd_networking.h"

#include <stdint.h>
#include <string.h>

// Helper function.
#define STRLEN(p) (sizeof(p) - 1)

/* Declare the MQTT thread stack space. */
static ULONG mqtt_client_stack[MQTT_CLIENT_STACK_SIZE / sizeof(ULONG)];

/* Declare buffers to hold message and topic. */
static UCHAR message_buffer[NXD_MQTT_MAX_MESSAGE_LENGTH];
static UCHAR topic_buffer[NXD_MQTT_MAX_TOPIC_NAME_LENGTH];


/* Declare the MQTT client control block. */
static NXD_MQTT_CLIENT mqtt_client;


/* Declare the disconnect notify function. */
static VOID client_disconnect_func(NXD_MQTT_CLIENT *client_ptr)
{
    NX_PARAMETER_NOT_USED(client_ptr);
    /* Clear the connected gate so the telemetry thread stops requesting
     * publishes until we reconnect. */
    tx_event_flags_set(&mqtt_app_flag, ~MQTT_CONNECTED, TX_AND);
    printf("client disconnected from broker.\r\n");
}

/* ------------------------------------------------------------------------
 * C1: cross-thread log path. Any thread may call mqtt_enqueue_log(), which
 * copies the JSON into a free ring slot and sends the slot index on log_queue.
 * ONLY the MQTT thread drains log_queue and calls the (static) publish, so the
 * NXD_MQTT_CLIENT is never touched concurrently from two threads.
 * ------------------------------------------------------------------------ */
#define LOG_RING_DEPTH 8
#define LOG_SLOT_SIZE  NXD_MQTT_MAX_MESSAGE_LENGTH   /* 320 */

/* Bounded publish/connect timeouts (ticks) so network stalls never hang a
 * thread forever (replaces the old NX_WAIT_FOREVER). */
#define MQTT_CONNECT_TIMEOUT  (10 * TX_TIMER_TICKS_PER_SECOND)
/* Max idle wait between event checks while connected; also the cadence at which
 * we re-check the connection is still up. */
#define MQTT_EVENT_WAIT       (5 * TX_TIMER_TICKS_PER_SECOND)

static char      log_ring[LOG_RING_DEPTH][LOG_SLOT_SIZE];
static UINT      log_ring_len[LOG_RING_DEPTH];
static ULONG     log_ring_head;            /* next slot to write (producer side) */
TX_QUEUE         mqtt_log_queue;           /* created in tx_application_define */
static ULONG     log_queue_storage[LOG_RING_DEPTH];
static ULONG     log_dropped;

/* Returns TX_TRUE while connected (peeks MQTT_CONNECTED without consuming it). */
UINT mqtt_is_connected(void)
{
    ULONG flags = 0;
    UINT status = tx_event_flags_get(&mqtt_app_flag, MQTT_CONNECTED, TX_AND,
                                     &flags, TX_NO_WAIT);
    return (status == TX_SUCCESS) ? TX_TRUE : TX_FALSE;
}

/* Producer: copy JSON into a ring slot, enqueue its index, signal the MQTT
 * thread. Safe from any thread; non-blocking. */
UINT mqtt_enqueue_log(const char* json, UINT length)
{
    if (json == NULL || length == 0) { return NX_PTR_ERROR; }
    if (length >= LOG_SLOT_SIZE) { length = LOG_SLOT_SIZE - 1; }

    /* Claim the next slot (interrupts off keeps head advance + copy atomic
     * w.r.t. other producers; producers are threads, not ISRs). */
    TX_INTERRUPT_SAVE_AREA
    TX_DISABLE
    ULONG slot = log_ring_head;
    log_ring_head = (log_ring_head + 1) % LOG_RING_DEPTH;
    TX_RESTORE

    memcpy(log_ring[slot], json, length);
    log_ring[slot][length] = '\0';
    log_ring_len[slot] = length;

    UINT status = tx_queue_send(&mqtt_log_queue, &slot, TX_NO_WAIT);
    if (status != TX_SUCCESS) { log_dropped++; return status; }
    tx_event_flags_set(&mqtt_app_flag, MQTT_LOG_READY, TX_OR);
    return TX_SUCCESS;
}

/* MQTT-thread-internal: publish one pre-formatted log JSON to MQTT_LOG_TOPIC.
 * Called ONLY from the MQTT thread (via drain), never cross-thread. */
static UINT mqtt_publish_log(const char* json, UINT length)
{
    if (mqtt_is_connected() != TX_TRUE)
    {
        return NXD_MQTT_NOT_CONNECTED;
    }
    UINT status = nxd_mqtt_client_publish(&mqtt_client,
                                          MQTT_LOG_TOPIC, STRLEN(MQTT_LOG_TOPIC),
                                          (CHAR*)json, length, 0, QOS1, NX_WAIT_FOREVER);
    if (status != NXD_MQTT_SUCCESS)
    {
        printf("Log publish failed with code: %d\r\n", status);
    }
    return status;
}

/* MQTT-thread-internal: drain all pending log slots and publish them. */
static void mqtt_drain_log_queue(void)
{
    ULONG slot;
    while (tx_queue_receive(&mqtt_log_queue, &slot, TX_NO_WAIT) == TX_SUCCESS)
    {
        if (slot < LOG_RING_DEPTH)
        {
            mqtt_publish_log(log_ring[slot], log_ring_len[slot]);
        }
    }
}

/* Create the cross-thread log queue. Called from tx_application_define before
 * the threads start. */
void mqtt_client_init(void)
{
    tx_queue_create(&mqtt_log_queue, "MQTT log queue", TX_1_ULONG,
                    log_queue_storage, sizeof(log_queue_storage));
}

static void send_message(){
    UINT status;
    
    /* Publish a message with QoS Level 1. */
    char buffer[TELEMETRY_BUFFER_SIZE] = {0};
    get_current_telemetry_string(buffer, sizeof(buffer));
    //printf("%s", buffer);

    status = nxd_mqtt_client_publish(&mqtt_client, MQTT_PUBLISH_TOPIC, STRLEN(MQTT_PUBLISH_TOPIC),
                                        (CHAR *)buffer, strlen(buffer), 0, QOS1, MQTT_CONNECT_TIMEOUT);

    if (status != NXD_MQTT_SUCCESS){
        printf("Publish failed with code: %d\r\n", status);
    }
    else{
        printf("Published message.\r\n");
    }
}

static void receive_message(){
    UINT status;
    UINT topic_length, message_length;

    status = nxd_mqtt_client_message_get(&mqtt_client, topic_buffer, sizeof(topic_buffer), &topic_length,
                                        message_buffer, sizeof(message_buffer), &message_length);
    printf("Received message and status: %d \r\n", status);
    if (status == NXD_MQTT_SUCCESS){
        topic_buffer[topic_length] = 0;
        message_buffer[message_length] = 0;
        printf("Topic: %s, Message: %s\r\n", topic_buffer, message_buffer);

        /* Show the incoming message on the OLED (header + wrapped payload). */
        screen_print_wrapped("TRAceON:", (const char*)message_buffer, message_length);
    }
}

static VOID client_notify_func(NXD_MQTT_CLIENT *client_ptr, UINT number_of_messages)
{
    NX_PARAMETER_NOT_USED(client_ptr);
    NX_PARAMETER_NOT_USED(number_of_messages);
    tx_event_flags_set(&mqtt_app_flag, MQTT_RECEIVE_EVENT, TX_OR);
    return;
}

static ULONG error_count;

/* Connect + subscribe + set receive-notify. Returns NXD_MQTT_SUCCESS when fully
 * set up; sets the MQTT_CONNECTED gate on success. Called for the initial
 * connect AND every reconnect. */
static UINT mqtt_connect_and_setup(NXD_ADDRESS *server_ip)
{
    UINT status = nxd_mqtt_client_connect(&mqtt_client, server_ip, NXD_MQTT_PORT,
                                          MQTT_KEEP_ALIVE_TIMER, 0, MQTT_CONNECT_TIMEOUT);
    if (status != NXD_MQTT_SUCCESS){
        printf("MQTT connect failed with code: %d\r\n", status);
        error_count++;
        return status;
    }
    printf("MQTT Client connected.\r\n");

    status = nxd_mqtt_client_subscribe(&mqtt_client, MQTT_SUBSCRIBE_TOPIC,
                                       STRLEN(MQTT_SUBSCRIBE_TOPIC), QOS0);
    if (status != NXD_MQTT_SUCCESS){
        printf("MQTT subscribe failed with code: %d\r\n", status);
        error_count++;
        nxd_mqtt_client_disconnect(&mqtt_client);
        return status;
    }
    printf("Subscribed to topic %s.\r\n", MQTT_SUBSCRIBE_TOPIC);

    status = nxd_mqtt_client_receive_notify_set(&mqtt_client, client_notify_func);
    if (status != NXD_MQTT_SUCCESS){
        printf("MQTT receive notify setup failed with code: %d\r\n", status);
        error_count++;
        nxd_mqtt_client_disconnect(&mqtt_client);
        return status;
    }

    /* Fully connected + subscribed: open the publish gate. */
    tx_event_flags_set(&mqtt_app_flag, MQTT_CONNECTED, TX_OR);
    return NXD_MQTT_SUCCESS;
}

static void mqtt_thread_work(NX_IP *ip_ptr, NX_PACKET_POOL *pool_ptr){
    UINT status;
    NXD_ADDRESS server_ip;
    ULONG events;
    ULONG backoff_s = 1;   /* reconnect backoff, exponential up to a cap */

    printf("Creating MQTT client\r\n");
    status = nxd_mqtt_client_create(&mqtt_client, MQTT_CLIENT_NAME, MQTT_CLIENT_NAME, STRLEN(MQTT_CLIENT_NAME),
                                    ip_ptr, pool_ptr, (VOID *)mqtt_client_stack, sizeof(mqtt_client_stack),
                                    MQTT_THREAD_PRIORTY, NX_NULL, 0);
    if (status){
        printf("Error in creating MQTT client: 0x%02x\n", status);
        error_count++;
    }
    printf(" MQTT client created\r\n");

    /* The disconnect callback clears MQTT_CONNECTED; this loop reconnects. */
    nxd_mqtt_client_disconnect_notify_set(&mqtt_client, client_disconnect_func);

    server_ip.nxd_ip_version = 4;
    server_ip.nxd_ip_address.v4 = MQTT_LOCAL_BROKER_IP;

    while (1){
        /* ---- Reconnect state machine ---- */
        if (mqtt_is_connected() != TX_TRUE){
            printf("MQTT not connected; attempting (re)connect...\r\n");
            if (mqtt_connect_and_setup(&server_ip) == NXD_MQTT_SUCCESS){
                backoff_s = 1;                       /* reset backoff on success */
                printf("MQTT connected; waiting for messages\r\n");
            } else {
                /* Bounded exponential backoff, capped at 30 s. */
                tx_thread_sleep(backoff_s * TX_TIMER_TICKS_PER_SECOND);
                if (backoff_s < 30) { backoff_s *= 2; if (backoff_s > 30) backoff_s = 30; }
                continue;                            /* retry */
            }
        }

        /* ---- Connected: service events with a bounded wait ---- */
        status = tx_event_flags_get(&mqtt_app_flag, MQTT_ALL_EVENTS, TX_OR_CLEAR,
                                    &events, MQTT_EVENT_WAIT);
        if (status == TX_NO_EVENTS){
            continue;   /* idle tick: loop back and re-check connection health */
        }
        if (events & MQTT_RECEIVE_EVENT){
            receive_message();
        }
        if (events & MQTT_MESSAGE_READY){
            send_message();
        }
        if (events & MQTT_LOG_READY){
            mqtt_drain_log_queue();   /* only the MQTT thread publishes logs */
        }
    }
}

void mqtt_thread_entry(ULONG parameter){

    UINT status;

    printf("Starting Eclipse ThreadX MQTT thread\r\n\r\n");

    /* Boot splash on the OLED (screen was initialized in board_init). Shows
     * before WiFi/MQTT so the display looks alive at startup. */
    screen_print_wrapped("TRAceON:", "logger", 6);

    // Initialize the network
    if ((status = wwd_network_init(WIFI_SSID, WIFI_PASSWORD, WIFI_MODE))){
        printf("ERROR: Failed to initialize the network (0x%08x)\r\n", status);
    }

    wwd_network_connect();

    mqtt_thread_work(&nx_ip, nx_pool);
}