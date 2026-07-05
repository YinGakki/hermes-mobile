cmake_minimum_required(VERSION 3.22.1)
project("hermespty")

# PTY JNI bridge — provides forkpty-like functionality for the terminal.
# Links against Android log library for __android_log_print.
add_library(hermespty SHARED pty.c)
target_link_libraries(hermespty log)
