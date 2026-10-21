package com.jarvis;

import com.jarvis.core.JarvisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"jarvis.desktop.enabled=false", "jarvis.intent.enabled=false"})
class SaludoEncodingTest {
    @Autowired JarvisService jarvis;

    /** jarvis.owner viene de application.properties: la ñ no debe salir como "Ã±". */
    @Test void enieCorrecta() {
        String r = jarvis.process("buenos días jarvis");
        System.out.println(r);
        assertTrue(r.contains("señor"), r);
    }
}
