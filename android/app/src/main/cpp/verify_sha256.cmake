# №44: сверка libcrypto.a с закреплённым хешем ПЕРЕД каждой линковкой (вызывается из CMakeLists.txt).
file(SHA256 "${LIB}" _got)
if(NOT _got STREQUAL EXPECT)
    message(FATAL_ERROR "№44: libcrypto.a ПОДМЕНЁН или повреждён\n  файл:     ${LIB}\n  ожидали:  ${EXPECT}\n  получили: ${_got}")
endif()
