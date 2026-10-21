package com.jarvis.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class CommandRunnerTest {

    private final CommandRunner seguro = new CommandRunner(false, 6000, 10_000, "");

    @ParameterizedTest
    @ValueSource(strings = {"format C:", "del archivo.txt", "del /s /q C:\\datos", "erase x", "rm -rf /", "rm archivo",
            "rmdir /s carpeta", "rd /s /q c:\\x", "shutdown /s /t 0", "diskpart", "echo hola && del x",
            "powershell -c \"Remove-Item -Recurse C:\\x\"", "reg delete HKCU\\x /f", "vssadmin delete shadows /all",
            "mkfs.ext4 /dev/sda1", "dd if=/dev/zero of=/dev/sda", ":(){ :|:& };:"})
    void bloqueaDestructivos(String cmd) throws Exception {
        assertTrue(seguro.esPeligroso(cmd), cmd);
        CommandRunner.Resultado r = seguro.ejecutar(cmd, null);
        assertTrue(r.bloqueado(), cmd);
        assertTrue(r.salida().startsWith("BLOQUEADO"), r.salida());
    }

    @ParameterizedTest
    @ValueSource(strings = {"dir", "echo hola", "ipconfig", "type notas.txt", "mkdir informe", "ls -la", "delta", "model", "format-table"})
    void permiteInofensivos(String cmd) {
        assertFalse(seguro.esPeligroso(cmd), cmd);
    }

    @Test void allowDangerousQuitaElFreno() {
        assertFalse(new CommandRunner(true, 6000, 10_000, "").esPeligroso("del x"));
    }

    @Test void ejecutaYDevuelveSalida() throws Exception {
        CommandRunner.Resultado r = seguro.ejecutar("echo hola mundo", Path.of(System.getProperty("java.io.tmpdir")));
        assertEquals(0, r.codigo());
        assertTrue(r.salida().contains("hola mundo"), r.salida());
    }

    /** Antes, mucha salida podía llenar el pipe y colgar el proceso. Debe terminar y recortar. */
    @Test @EnabledOnOs(OS.WINDOWS)
    void muchaSalidaNoSeCuelga() {
        CommandRunner corto = new CommandRunner(false, 2000, 30_000, "");
        CommandRunner.Resultado r = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> corto.ejecutar("for /L %i in (1,1,30000) do @echo linea numero %i con bastante texto", null));
        assertEquals(0, r.codigo());
        assertTrue(r.salida().endsWith("…(salida recortada)"), "debe recortar");
        assertTrue(r.salida().startsWith("linea numero 1 "));
    }

    /** El timeout tiene que cortar de verdad (antes se esperaba a leer toda la salida primero). */
    @Test @EnabledOnOs(OS.WINDOWS)
    void timeoutCancelaElComando() {
        CommandRunner rapido = new CommandRunner(false, 2000, 1500, "");
        CommandRunner.Resultado r = assertTimeoutPreemptively(Duration.ofSeconds(15),
                () -> rapido.ejecutar("ping -n 30 127.0.0.1", null));
        assertTrue(r.timeout());
        assertTrue(r.salida().contains("se canceló"));
    }

    /** cmd.exe escribe en la página OEM: "dir" de una carpeta con tildes debe leerse bien. */
    @Test @EnabledOnOs(OS.WINDOWS)
    void leeTildesConLaCodificacionDeConsola() throws Exception {
        assertNotEquals("UTF-8", seguro.charset().name().toUpperCase(), "en Windows no debe asumir UTF-8 (salvo chcp 65001)");
        Path dir = java.nio.file.Files.createTempDirectory("jarvis-cmd");
        java.nio.file.Files.createDirectory(dir.resolve("año pequeño"));
        CommandRunner.Resultado r = seguro.ejecutar("dir /b", dir);
        assertTrue(r.salida().contains("año pequeño"), r.salida());
    }
}
