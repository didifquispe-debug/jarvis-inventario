package com.jarvis;

import com.jarvis.core.JarvisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "jarvis.desktop.enabled=false")
class JarvisServiceTest {

    @Autowired JarvisService jarvis;

    @Test void hora() { assertTrue(jarvis.process("JARVIS, ¿qué hora es?").startsWith("Son las")); }
    @Test void calculo() { assertTrue(jarvis.process("cuánto es 25 por 4 más 10").contains("110")); }
    @Test void saludo() { assertTrue(jarvis.process("Hola JARVIS").contains("¿En qué le ayudo?")); }
    @Test void ayuda() { assertTrue(jarvis.process("ayuda").contains("cálculos")); }
}
