cmake_minimum_required(VERSION 3.20)
project(sdncustom_opcua_bridge C)

find_package(open62541 CONFIG REQUIRED)

add_library(sdncustom_opcua SHARED src/sdn_opcua_bridge.c)
target_link_libraries(sdncustom_opcua PRIVATE open62541::open62541)
target_compile_features(sdncustom_opcua PRIVATE c_std_99)
