#  Copyright (c) Microsoft
#  Copyright (c) 2024 Eclipse Foundation
# 
#  This program and the accompanying materials are made available 
#  under the terms of the MIT license which is available at
#  https://opensource.org/license/mit.
# 
#  SPDX-License-Identifier: MIT
# 
#  Contributors: 
#     Microsoft         - Initial version
#     Frédéric Desbiens - 2024 version.

if(NOT APP_CONFIG)
    set(APP_CONFIG "mqtt")
endif()

if(NOT APP_CONFIG STREQUAL "mqtt")
    message(FATAL_ERROR "This project has been trimmed to the 'mqtt' app only. "
                        "APP_CONFIG='${APP_CONFIG}' is no longer available.")
endif()

message(STATUS "Building application with configuration: ${APP_CONFIG}")

set(COMMON_DIR ${CMAKE_CURRENT_SOURCE_DIR}/common)
set(CONFIG_DIR ${CMAKE_CURRENT_SOURCE_DIR}/${APP_CONFIG})

# Common sources for all configurations
set(SOURCES
    ${COMMON_DIR}/startup/system_stm32f4xx.c
    ${COMMON_DIR}/startup/startup_stm32f412rx.s
    ${COMMON_DIR}/startup/tx_initialize_low_level.S
    ${COMMON_DIR}/stm32cubef4/stm32f4xx_hal_msp.c
    ${COMMON_DIR}/console.c
    ${COMMON_DIR}/sntp_client.c
    ${COMMON_DIR}/wwd_networking.c
)

# MQTT application sources (self-contained in app/mqtt).
list(APPEND SOURCES
    ${COMMON_DIR}/board_init.c
    ${COMMON_DIR}/screen.c
    ${COMMON_DIR}/logger.c
    ${CONFIG_DIR}/telemetry.c
    ${CONFIG_DIR}/mqtt_client.c
    ${CONFIG_DIR}/main.c
    ${CONFIG_DIR}/cloud_config.h
)

add_executable(${PROJECT_NAME} ${SOURCES})

target_link_libraries(${PROJECT_NAME}
    azrtos::threadx
    azrtos::netxduo

    stm32cubef4
    mxchip_bsp
    app_common
    wiced_sdk
    nanoprintf
)

target_include_directories(${PROJECT_NAME} 
    PUBLIC 
        ${COMMON_DIR}
        ${CONFIG_DIR}
)

# Use absolute path to the linker script to support older CMake versions
if(CMAKE_C_COMPILER_ID STREQUAL "IAR")
    message(FATAL_ERROR, "IAR is not currently implemented for this device")
else()
    set_target_linker(${PROJECT_NAME} ${COMMON_DIR}/startup/MXChip_AZ3166.ld)
endif()

post_build(${PROJECT_NAME})
