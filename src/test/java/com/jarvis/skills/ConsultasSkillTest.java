/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.skills;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class ConsultasSkillTest {

    private static final LocalDate MIERCOLES = LocalDate.of(2026, 11, 4);

    private static ConsultasSkill.Periodo p(String cmd) { return ConsultasSkill.periodo(cmd, MIERCOLES, "hoy"); }

    @Test void periodosDichos() {
        assertEquals(new ConsultasSkill.Periodo(MIERCOLES, MIERCOLES, "hoy"), p("cuanto gane"));
        assertEquals(LocalDate.of(2026, 11, 3), p("cuanto gane ayer").desde());
        assertEquals(LocalDate.of(2026, 11, 2), p("cuanto gane esta semana").desde(), "la semana empieza el lunes");
        assertEquals(new ConsultasSkill.Periodo(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1), "la semana pasada"),
                p("cuanto vendi la semana pasada"));
        assertEquals(LocalDate.of(2026, 11, 1), p("ganancia del mes").desde());
        assertEquals(new ConsultasSkill.Periodo(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "el mes pasado"),
                p("cuanto gane el mes pasado"));
        assertEquals(LocalDate.of(2026, 1, 1), p("ventas de este ano").desde());
    }
}
