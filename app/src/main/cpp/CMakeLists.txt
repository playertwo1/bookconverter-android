cmake_minimum_required(VERSION 3.22.1)
project(mobi_jni C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(LIBMOBI_DIR ${CMAKE_CURRENT_SOURCE_DIR}/libmobi/src)

add_definitions(
    -DPACKAGE_VERSION="0.12"
    -DUSE_ENCRYPTION
    -DUSE_XMLWRITER
    -DHAVE_UNISTD_H
    -DHAVE_STRDUP
    -DHAVE_SYS_RANDOM_H
    -DHAVE_GETRANDOM
    -DHAVE_ATTRIBUTE_NORETURN
    -DMOBI_INLINE=inline
)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${LIBMOBI_DIR}
)

set(LIBMOBI_SOURCES
    ${LIBMOBI_DIR}/buffer.c
    ${LIBMOBI_DIR}/compression.c
    ${LIBMOBI_DIR}/debug.c
    ${LIBMOBI_DIR}/encryption.c
    ${LIBMOBI_DIR}/index.c
    ${LIBMOBI_DIR}/memory.c
    ${LIBMOBI_DIR}/meta.c
    ${LIBMOBI_DIR}/opf.c
    ${LIBMOBI_DIR}/parse_rawml.c
    ${LIBMOBI_DIR}/randombytes.c
    ${LIBMOBI_DIR}/read.c
    ${LIBMOBI_DIR}/sha1.c
    ${LIBMOBI_DIR}/structure.c
    ${LIBMOBI_DIR}/util.c
    ${LIBMOBI_DIR}/write.c
    ${LIBMOBI_DIR}/xmlwriter.c
)

add_library(
    mobi_jni
    SHARED
    mobi_jni.c
    ${LIBMOBI_SOURCES}
)

target_link_libraries(
    mobi_jni
    log
    z
)
