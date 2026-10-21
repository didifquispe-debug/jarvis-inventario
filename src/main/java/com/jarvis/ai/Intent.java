package com.jarvis.ai;

/** Intenciones de negocio que el clasificador local sabe reconocer. */
public enum Intent {
    REGISTRAR_ENTRADA,
    REGISTRAR_SALIDA,
    CONSULTAR_STOCK,
    REPORTE_DIARIO,
    /** Cualquier otra cosa (hora, cálculos, abrir apps…) o confianza insuficiente. */
    DESCONOCIDO
}
