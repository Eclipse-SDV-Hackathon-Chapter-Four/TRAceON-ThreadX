/* Host-test shim: real mqtt_client.h pulls in NetXDuo. The test driver already
 * defined MQTT_CLIENT_H and declared mqtt_publish_log, so this is intentionally
 * empty — it only needs to exist so logger.c's #include resolves on the host. */
