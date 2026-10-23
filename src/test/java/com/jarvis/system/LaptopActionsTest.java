package com.jarvis.system;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LaptopActionsTest {

    @ParameterizedTest
    @ValueSource(strings = {"https://www.youtube.com", "https://www.google.com/search?q=hola+mundo&hl=es",
            "http://192.168.1.35:8081/"})
    void aceptaWebsNormales(String url) {
        assertNotNull(LaptopActions.urlSegura(url), url);
    }

    /** Lo que alguien podría dictar o escribir para colarse a la terminal o al disco. */
    @ParameterizedTest
    @ValueSource(strings = {"https://x.com & del /q C:\\datos", "https://x.com|calc", "file:///C:/Windows/System32/cmd.exe",
            "javascript:alert(1)", "calc", "ms-settings:", "https://x.com\"&calc", "", "https://"})
    void rechazaUrlsPeligrosas(String url) {
        assertNull(LaptopActions.urlSegura(url), url);
    }
}
