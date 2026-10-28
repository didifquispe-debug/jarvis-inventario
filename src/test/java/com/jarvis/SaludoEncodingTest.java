/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis;

import com.jarvis.core.JarvisService;
import com.jarvis.skills.GreetingSkill;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"jarvis.desktop.enabled=false", "jarvis.intent.enabled=false"})
class SaludoEncodingTest {
    @Autowired JarvisService jarvis;
    @Autowired GreetingSkill saludo;

    @Test void enieCorrecta() {
        String r = jarvis.process("buenos días jarvis");
        System.out.println(r);
        assertTrue(r.contains("señor"), r);
    }

    @Test void bienvenidaAlAbrir() {
        String r = saludo.bienvenida();
        System.out.println(r);
        assertTrue(r.matches("(Buenos días|Buenas tardes|Buenas noches), señor\\. .*"), r);
        assertTrue(r.contains("en línea"), r);
    }
}
