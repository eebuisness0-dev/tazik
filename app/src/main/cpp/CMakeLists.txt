cmake_minimum_required(VERSION 3.22)
project(tazikqemu C)

add_library(tazikqemu SHARED qemu_bridge.c)

target_compile_options(tazikqemu PRIVATE
    -O2
    -Wall
    -Wextra
    -std=gnu11
)

target_link_libraries(tazikqemu
    android
    log
)

target_link_options(tazikqemu PRIVATE
    "-Wl,-z,max-page-size=16384"
)
