if(NOT DEFINED NM_TOOL OR NOT EXISTS "${NM_TOOL}")
    message(FATAL_ERROR "llvm-nm was not supplied: ${NM_TOOL}")
endif()

if(NOT DEFINED LOADER_FILE OR NOT EXISTS "${LOADER_FILE}")
    message(FATAL_ERROR "Loader ELF was not supplied: ${LOADER_FILE}")
endif()

if(NOT DEFINED OUTPUT_FILE)
    message(FATAL_ERROR "OUTPUT_FILE was not supplied")
endif()

execute_process(
    COMMAND "${NM_TOOL}" --defined-only --numeric-sort "${LOADER_FILE}"
    RESULT_VARIABLE nm_result
    OUTPUT_VARIABLE symbols
    ERROR_VARIABLE nm_error
)
if(NOT nm_result EQUAL 0)
    message(FATAL_ERROR "llvm-nm failed: ${nm_error}")
endif()

string(REGEX MATCH "([0-9A-Fa-f]+)[ \t]+[A-Za-z][ \t]+_start([\r\n]|$)" start_match "${symbols}")
set(start_address "${CMAKE_MATCH_1}")
string(REGEX MATCH "([0-9A-Fa-f]+)[ \t]+[A-Za-z][ \t]+pokedata_workaround([\r\n]|$)" workaround_match "${symbols}")
set(workaround_address "${CMAKE_MATCH_1}")

if(start_address STREQUAL "" OR workaround_address STREQUAL "")
    message(FATAL_ERROR "Required loader symbols were not found in ${LOADER_FILE}")
endif()

math(EXPR workaround_offset "0x${workaround_address} - 0x${start_address}")
file(WRITE "${OUTPUT_FILE}"
    "#include <unistd.h>\nconst ssize_t offset_to_pokedata_workaround=${workaround_offset};\n")
