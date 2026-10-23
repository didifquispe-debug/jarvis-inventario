package com.jarvis.inventory;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Reportes en Excel (.xlsx), con el estilo del LibroExcel del sistema de referencia: título, cabecera de color,
 * filas congeladas, autofiltro y totales con SUBTOTAL (que respetan el filtro).
 * <ul>
 *   <li>Inventario valorizado (al costo y a precio de venta) con el kardex de todos los productos.</li>
 *   <li>Kardex de un producto.</li>
 *   <li>Ventas de un periodo: comprobantes y detalle con el costo y la ganancia de cada línea (sin IGV).</li>
 *   <li>Compras de un periodo: compras y detalle.</li>
 * </ul>
 * Los comprobantes y compras anulados figuran con importe 0 (el original va en "Estado"), así los totales no los suman.
 */
public final class ReportesExcel {

    private ReportesExcel() {}

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final BigDecimal UNO_MAS_IGV = BigDecimal.ONE.add(Igv.TASA);

    /** Cómo se llama cada tipo de movimiento de stock en el kardex. */
    static final Map<String, String> MOVIMIENTO = Map.of("ENTRADA", "Entrada", "VENTA", "Venta", "ANULACION", "Anulación de venta",
            "CORRECCION_VENTA", "Corrección de venta", "AJUSTE", "Ajuste", "COMPRA", "Compra",
            "ANULACION_COMPRA", "Anulación de compra", "ELIMINAR", "Baja del producto");

    // ---------------------------------------------------------------- libros

    public static byte[] inventario(InventoryService.Valorizado v, List<FilaKardex> kardex, String negocio) {
        try (XSSFWorkbook libro = new XSSFWorkbook()) {
            Estilos e = new Estilos(libro);
            Sheet hoja = libro.createSheet("Inventario");
            String[] t = {"Producto", "Categoría", "Código", "Unidad", "Stock", "Stock mínimo", "Estado", "Costo promedio",
                    "Valor al costo", "Precio de venta", "Valor a precio de venta"};
            int f = encabezado(hoja, e, "Inventario valorizado", subtitulo(negocio, "al " + LocalDate.now().format(DMY)), t,
                    new int[]{34, 16, 14, 11, 10, 12, 11, 14, 15, 14, 18});
            int primera = f;
            for (InventoryService.FilaValorizada p : v.filas()) {
                Row r = hoja.createRow(f++);
                texto(r, 0, p.producto(), e.celda);
                texto(r, 1, p.categoria().isEmpty() ? "Sin categoría" : p.categoria(), e.celda);
                texto(r, 2, p.codigo(), e.celda);
                texto(r, 3, p.unidad(), e.celda);
                numero(r, 4, p.stock(), e.cantidad);
                numero(r, 5, p.stockMin(), e.cantidad);
                texto(r, 6, switch (p.estado()) { case "SIN_STOCK" -> "SIN STOCK"; case "BAJO" -> "BAJO"; default -> "OK"; },
                        p.estado().equals("OK") ? e.estadoOk : p.estado().equals("BAJO") ? e.estadoAviso : e.estadoMal);
                if (p.costo() != null) numero(r, 7, p.costo(), e.moneda); else texto(r, 7, "sin costo", e.suave);
                if (p.valorCosto() != null) numero(r, 8, p.valorCosto(), e.moneda); else texto(r, 8, "", e.celda);
                numero(r, 9, p.precio(), e.moneda);
                numero(r, 10, p.valorVenta(), e.moneda);
            }
            cerrar(hoja, e, t.length, primera, f, new int[]{8, 10});

            int c = t.length + 1;
            hoja.setColumnWidth(c, 30 * 256);
            hoja.setColumnWidth(c + 1, 16 * 256);
            Object[][] resumen = {
                    {"Productos", v.productos()}, {"Con stock bajo", v.stockBajo()}, {"Sin stock", v.sinStock()},
                    {"Sin costo registrado", v.sinCosto()}, {"Valor al costo", v.valorCosto()},
                    {"Valor a precio de venta", v.valorVenta()}, {"Margen (productos con costo)", v.margen()}};
            Row cab = fila(hoja, primera - 1);
            texto(cab, c, "Resumen", e.cabecera);
            texto(cab, c + 1, "", e.cabecera);
            for (int i = 0; i < resumen.length; i++) {
                Row r = fila(hoja, primera + i);
                texto(r, c, (String) resumen[i][0], e.celdaNegrita);
                if (resumen[i][1] instanceof BigDecimal b) numero(r, c + 1, b, e.monedaNegrita);
                else entero(r, c + 1, (Integer) resumen[i][1], e.entero);
            }
            hojaKardex(libro, e, kardex, "Kardex", "Kardex de todos los productos", negocio, true);
            return bytes(libro, "Inventario valorizado");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static byte[] kardex(InventoryService.Kardex k, String negocio) {
        try (XSSFWorkbook libro = new XSSFWorkbook()) {
            hojaKardex(libro, new Estilos(libro), k.filas(), "Kardex", "Kardex: " + k.producto() + " (" + k.unidad() + ")", negocio, false);
            return bytes(libro, "Kardex " + k.producto());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static byte[] ventas(List<InventoryService.Comprobante> comprobantes, String desde, String hasta, String negocio) {
        try (XSSFWorkbook libro = new XSSFWorkbook()) {
            Estilos e = new Estilos(libro);
            String periodo = periodo(desde, hasta);
            Sheet hoja = libro.createSheet("Ventas");
            String[] t = {"Fecha", "Hora", "Comprobante", "Tipo", "Cliente", "Documento", "Op. gravada", "IGV", "Total", "Estado"};
            int f = encabezado(hoja, e, "Ventas", subtitulo(negocio, periodo), t, new int[]{12, 8, 16, 14, 30, 14, 14, 12, 14, 22});
            int primera = f;
            for (InventoryService.Comprobante c : comprobantes) {
                Row r = hoja.createRow(f++);
                fecha(r, 0, c.fecha, e);
                texto(r, 1, c.hora, e.celda);
                texto(r, 2, c.codigo.isEmpty() ? "N° " + c.numero : c.codigo, c.anulado ? e.anulada : e.celdaNegrita);
                texto(r, 3, TipoComprobante.valueOf(c.tipo).descripcion.replace(" electrónica", ""), e.celda);
                texto(r, 4, c.cliente == null ? "Clientes varios" : c.cliente.nombre(), e.celda);
                texto(r, 5, c.cliente == null ? "" : c.cliente.tipoDocumento() + " " + c.cliente.documento(), e.celda);
                BigDecimal cero = BigDecimal.ZERO;
                numero(r, 6, c.anulado ? cero : c.base, e.moneda);
                numero(r, 7, c.anulado ? cero : c.igv, e.moneda);
                numero(r, 8, c.anulado ? cero : c.total, e.monedaNegrita);
                texto(r, 9, c.anulado ? "ANULADO (S/ " + c.total + ")" : "EMITIDO", c.anulado ? e.estadoMal : e.estadoOk);
            }
            cerrar(hoja, e, t.length, primera, f, new int[]{6, 7, 8});

            Sheet det = libro.createSheet("Detalle");
            String[] td = {"Fecha", "Comprobante", "Cliente", "Producto", "Cantidad", "Unidad", "Precio unitario", "Importe",
                    "Venta sin IGV", "Costo unitario", "Costo total", "Ganancia"};
            f = encabezado(det, e, "Detalle de ventas", subtitulo(negocio, periodo + " · sin comprobantes anulados · ganancia = venta sin IGV − costo"),
                    td, new int[]{12, 16, 26, 34, 10, 11, 14, 13, 14, 14, 13, 13});
            primera = f;
            for (InventoryService.Comprobante c : comprobantes) {
                if (c.anulado) continue;
                for (InventoryService.Venta v : c.items) {
                    Row r = det.createRow(f++);
                    fecha(r, 0, c.fecha, e);
                    texto(r, 1, c.codigo.isEmpty() ? "N° " + c.numero : c.codigo, e.celda);
                    texto(r, 2, c.cliente == null ? "Clientes varios" : c.cliente.nombre(), e.celda);
                    texto(r, 3, v.producto, e.celda);
                    numero(r, 4, v.cantidad, e.cantidad);
                    texto(r, 5, v.unidad, e.celda);
                    numero(r, 6, v.precioUnitario, e.moneda);
                    numero(r, 7, v.monto, e.moneda);
                    BigDecimal sinIgv = "INCLUIDO".equals(c.modoIgv) ? v.monto.divide(UNO_MAS_IGV, 2, RoundingMode.HALF_UP) : v.monto;
                    numero(r, 8, sinIgv, e.moneda);
                    if (v.costoUnitario != null) {
                        BigDecimal costo = Num.dinero(v.costoUnitario.multiply(v.cantidad));
                        numero(r, 9, v.costoUnitario, e.moneda);
                        numero(r, 10, costo, e.moneda);
                        numero(r, 11, sinIgv.subtract(costo), e.monedaNegrita);
                    } else {
                        texto(r, 9, "sin costo", e.suave);
                        texto(r, 10, "", e.celda);
                        texto(r, 11, "", e.celda);
                    }
                }
            }
            cerrar(det, e, td.length, primera, f, new int[]{7, 8, 10, 11});
            return bytes(libro, "Ventas " + periodo);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static byte[] compras(List<Compras.Compra> compras, String desde, String hasta, String negocio) {
        try (XSSFWorkbook libro = new XSSFWorkbook()) {
            Estilos e = new Estilos(libro);
            String periodo = periodo(desde, hasta);
            Sheet hoja = libro.createSheet("Compras");
            String[] t = {"Fecha", "Compra", "Proveedor", "RUC", "Documento", "Número", "Fecha doc.", "Op. gravada", "IGV", "Total", "Estado"};
            int f = encabezado(hoja, e, "Compras", subtitulo(negocio, periodo), t, new int[]{12, 14, 30, 14, 16, 14, 12, 14, 12, 14, 22});
            int primera = f;
            List<Compras.Compra> orden = compras.stream().sorted((a, b) -> Integer.compare(a.numero(), b.numero())).toList();
            for (Compras.Compra c : orden) {
                Row r = hoja.createRow(f++);
                fecha(r, 0, c.fecha(), e);
                texto(r, 1, c.codigo(), c.anulada() ? e.anulada : e.celdaNegrita);
                texto(r, 2, c.proveedor(), e.celda);
                texto(r, 3, c.proveedorRuc(), e.celda);
                texto(r, 4, c.tipoDocumentoDescripcion(), e.celda);
                texto(r, 5, c.numeroDocumento(), e.celda);
                fecha(r, 6, c.fechaDocumento(), e);
                BigDecimal cero = BigDecimal.ZERO;
                numero(r, 7, c.anulada() ? cero : c.base(), e.moneda);
                numero(r, 8, c.anulada() ? cero : c.igv(), e.moneda);
                numero(r, 9, c.anulada() ? cero : c.total(), e.monedaNegrita);
                texto(r, 10, c.anulada() ? "ANULADA (S/ " + c.total() + ")" : "REGISTRADA", c.anulada() ? e.estadoMal : e.estadoOk);
            }
            cerrar(hoja, e, t.length, primera, f, new int[]{7, 8, 9});

            Sheet det = libro.createSheet("Detalle");
            String[] td = {"Fecha", "Compra", "Proveedor", "Producto", "Cantidad", "Unidad", "Costo unitario", "Importe"};
            f = encabezado(det, e, "Detalle de compras", subtitulo(negocio, periodo + " · sin compras anuladas"), td,
                    new int[]{12, 14, 30, 34, 10, 11, 15, 14});
            primera = f;
            for (Compras.Compra c : orden) {
                if (c.anulada()) continue;
                for (Compras.Detalle d : c.detalles()) {
                    Row r = det.createRow(f++);
                    fecha(r, 0, c.fecha(), e);
                    texto(r, 1, c.codigo(), e.celda);
                    texto(r, 2, c.proveedor(), e.celda);
                    texto(r, 3, d.producto(), e.celda);
                    numero(r, 4, d.cantidad(), e.cantidad);
                    texto(r, 5, d.unidad(), e.celda);
                    numero(r, 6, d.costo(), e.costo);
                    numero(r, 7, d.importe(), e.moneda);
                }
            }
            cerrar(det, e, td.length, primera, f, new int[]{7});
            return bytes(libro, "Compras " + periodo);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    // ---------------------------------------------------------------- hojas y tablas

    private static void hojaKardex(XSSFWorkbook libro, Estilos e, List<FilaKardex> filas, String nombreHoja, String titulo,
                                   String negocio, boolean conProducto) {
        Sheet hoja = libro.createSheet(nombreHoja);
        String[] t = conProducto
                ? new String[]{"Producto", "Fecha", "Movimiento", "Detalle", "Entrada", "Salida", "Saldo", "Costo promedio", "Valor del saldo", "Usuario"}
                : new String[]{"Fecha", "Movimiento", "Detalle", "Entrada", "Salida", "Saldo", "Costo promedio", "Valor del saldo", "Usuario"};
        int[] anchos = conProducto ? new int[]{30, 17, 20, 44, 10, 10, 10, 14, 15, 18} : new int[]{17, 20, 48, 10, 10, 10, 14, 15, 18};
        int f = encabezado(hoja, e, titulo, subtitulo(negocio, "movimientos de stock · método del costo promedio"), t, anchos);
        int primera = f, o = conProducto ? 1 : 0;
        for (FilaKardex k : filas) {
            Row r = hoja.createRow(f++);
            if (conProducto) texto(r, 0, k.producto(), e.celda);
            fechaHora(r, o, k.fecha(), e);
            texto(r, o + 1, MOVIMIENTO.getOrDefault(k.tipo(), k.tipo()), e.celda);
            texto(r, o + 2, k.detalle(), e.suave);
            numero(r, o + 3, k.entrada(), e.cantidad);
            numero(r, o + 4, k.salida(), e.cantidad);
            if (k.saldo() != null) numero(r, o + 5, k.saldo(), e.cantidadNegrita); else texto(r, o + 5, "—", e.suave);
            if (k.costo() != null) numero(r, o + 6, k.costo(), e.moneda); else texto(r, o + 6, "", e.celda);
            if (k.costo() != null && k.saldo() != null) numero(r, o + 7, Num.dinero(k.costo().multiply(k.saldo())), e.moneda);
            else texto(r, o + 7, "", e.celda);
            texto(r, o + 8, k.usuario(), e.suave);
        }
        cerrar(hoja, e, t.length, primera, f, new int[]{});
    }

    /** Título, subtítulo y cabecera de la tabla; devuelve la primera fila de datos. */
    private static int encabezado(Sheet hoja, Estilos e, String titulo, String subtitulo, String[] titulos, int[] anchos) {
        Row r0 = hoja.createRow(0);
        r0.setHeightInPoints(26);
        texto(r0, 0, titulo, e.titulo);
        texto(hoja.createRow(1), 0, subtitulo, e.subtitulo);
        Row cab = hoja.createRow(3);
        cab.setHeightInPoints(22);
        for (int i = 0; i < titulos.length; i++) texto(cab, i, titulos[i], e.cabecera);
        for (int i = 0; i < anchos.length; i++) hoja.setColumnWidth(i, anchos[i] * 256);
        hoja.createFreezePane(0, 4);
        return 4;
    }

    /** Autofiltro y fila de totales con SUBTOTAL(109, …): suma solo lo que queda visible al filtrar. */
    private static void cerrar(Sheet hoja, Estilos e, int columnas, int primera, int siguiente, int[] importes) {
        if (siguiente > primera) hoja.setAutoFilter(new CellRangeAddress(primera - 1, siguiente - 1, 0, columnas - 1));
        if (importes.length == 0) return;
        Row r = hoja.createRow(siguiente);
        for (int c = 0; c < columnas; c++) r.createCell(c).setCellStyle(e.total);
        texto(r, 0, "TOTAL", e.total);
        for (int c : importes) {
            Cell celda = r.createCell(c);
            String col = CellReference.convertNumToColString(c);
            celda.setCellFormula(siguiente > primera ? "SUBTOTAL(109," + col + (primera + 1) + ":" + col + siguiente + ")" : "0");
            celda.setCellStyle(e.totalMoneda);
        }
    }

    // ---------------------------------------------------------------- celdas

    private static Row fila(Sheet hoja, int n) {
        Row r = hoja.getRow(n);
        return r != null ? r : hoja.createRow(n);
    }

    private static void texto(Row r, int c, String v, CellStyle s) {
        Cell celda = r.createCell(c);
        celda.setCellValue(v == null ? "" : v);
        celda.setCellStyle(s);
    }

    private static void numero(Row r, int c, BigDecimal v, CellStyle s) {
        Cell celda = r.createCell(c);
        celda.setCellValue(v == null ? 0 : v.doubleValue());
        celda.setCellStyle(s);
    }

    private static void entero(Row r, int c, long v, CellStyle s) {
        Cell celda = r.createCell(c);
        celda.setCellValue(v);
        celda.setCellStyle(s);
    }

    /** "2026-10-23" como fecha de Excel (se puede ordenar y filtrar); si no se entiende, como texto. */
    private static void fecha(Row r, int c, String iso, Estilos e) {
        try {
            Cell celda = r.createCell(c);
            celda.setCellValue(LocalDate.parse(iso));
            celda.setCellStyle(e.fecha);
        } catch (RuntimeException ex) {
            texto(r, c, iso, e.celda);
        }
    }

    private static void fechaHora(Row r, int c, String iso, Estilos e) {
        try {
            Cell celda = r.createCell(c);
            celda.setCellValue(LocalDateTime.parse(iso));
            celda.setCellStyle(e.fechaHora);
        } catch (RuntimeException ex) {
            texto(r, c, iso, e.celda);
        }
    }

    private static String subtitulo(String negocio, String detalle) {
        return (negocio == null || negocio.isBlank() ? "" : negocio + " · ") + detalle + " · generado el "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    }

    private static String periodo(String desde, String hasta) {
        boolean d = desde != null && !desde.isBlank(), h = hasta != null && !hasta.isBlank();
        if (!d && !h) return "todo el historial";
        return (d ? "del " + LocalDate.parse(desde.trim()).format(DMY) : "hasta") + (d && h ? " al " : " ")
                + (h ? LocalDate.parse(hasta.trim()).format(DMY) : "en adelante");
    }

    private static byte[] bytes(XSSFWorkbook libro, String titulo) throws IOException {
        libro.getProperties().getCoreProperties().setTitle(titulo);
        libro.getProperties().getCoreProperties().setCreator("J.A.R.V.I.S.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        libro.write(out);
        return out.toByteArray();
    }

    /** Estilos del libro (POI limita la cantidad de estilos por archivo: se crean una vez). */
    private static final class Estilos {
        final XSSFCellStyle titulo, subtitulo, cabecera, celda, celdaNegrita, suave, anulada, entero, cantidad, cantidadNegrita;
        final XSSFCellStyle moneda, monedaNegrita, costo, fecha, fechaHora, estadoOk, estadoAviso, estadoMal, total, totalMoneda;

        Estilos(XSSFWorkbook libro) {
            XSSFColor acento = color(10, 126, 164), borde = color(216, 222, 228), texto = color(19, 32, 47);
            DataFormat df = libro.createDataFormat();
            titulo = estilo(libro, fuente(libro, 16, true, texto), null, null);
            subtitulo = estilo(libro, fuente(libro, 10, false, color(90, 104, 121)), null, null);
            cabecera = estilo(libro, fuente(libro, 10, true, color(255, 255, 255)), acento, null);
            XSSFFont normal = fuente(libro, 10, false, texto), negrita = fuente(libro, 10, true, texto);
            celda = estilo(libro, normal, null, borde);
            celdaNegrita = estilo(libro, negrita, null, borde);
            suave = estilo(libro, fuente(libro, 9, false, color(120, 132, 146)), null, borde);
            XSSFFont tachada = fuente(libro, 10, false, color(140, 149, 159));
            tachada.setStrikeout(true);
            anulada = estilo(libro, tachada, null, borde);
            entero = formato(libro, normal, borde, df.getFormat("#,##0"));
            cantidad = formato(libro, normal, borde, df.getFormat("#,##0.###"));
            cantidadNegrita = formato(libro, negrita, borde, df.getFormat("#,##0.###"));
            moneda = formato(libro, normal, borde, df.getFormat("\"S/\" #,##0.00"));
            monedaNegrita = formato(libro, negrita, borde, df.getFormat("\"S/\" #,##0.00"));
            costo = formato(libro, normal, borde, df.getFormat("\"S/\" #,##0.00##"));
            fecha = formato(libro, normal, borde, df.getFormat("dd/mm/yyyy"));
            fechaHora = formato(libro, normal, borde, df.getFormat("dd/mm/yyyy hh:mm"));
            estadoOk = estilo(libro, fuente(libro, 9, true, color(21, 128, 77)), color(226, 243, 233), borde);
            estadoAviso = estilo(libro, fuente(libro, 9, true, color(159, 95, 16)), color(251, 239, 220), borde);
            estadoMal = estilo(libro, fuente(libro, 9, true, color(192, 53, 46)), color(251, 230, 228), borde);
            for (XSSFCellStyle s : new XSSFCellStyle[]{estadoOk, estadoAviso, estadoMal}) s.setAlignment(HorizontalAlignment.CENTER);
            total = estilo(libro, fuente(libro, 10, true, acento), color(225, 241, 247), null);
            total.setBorderTop(BorderStyle.MEDIUM);
            total.setTopBorderColor(acento);
            totalMoneda = libro.createCellStyle();
            totalMoneda.cloneStyleFrom(total);
            totalMoneda.setDataFormat(df.getFormat("\"S/\" #,##0.00"));
        }

        private static XSSFCellStyle formato(XSSFWorkbook libro, XSSFFont fuente, XSSFColor borde, short formato) {
            XSSFCellStyle s = estilo(libro, fuente, null, borde);
            s.setDataFormat(formato);
            return s;
        }

        private static XSSFCellStyle estilo(XSSFWorkbook libro, XSSFFont fuente, XSSFColor fondo, XSSFColor borde) {
            XSSFCellStyle s = libro.createCellStyle();
            s.setFont(fuente);
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            if (fondo != null) {
                s.setFillForegroundColor(fondo);
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            if (borde != null) {
                s.setBorderBottom(BorderStyle.THIN);
                s.setBottomBorderColor(borde);
            }
            return s;
        }

        private static XSSFFont fuente(XSSFWorkbook libro, int puntos, boolean negrita, XSSFColor color) {
            XSSFFont f = libro.createFont();
            f.setFontName("Calibri");
            f.setFontHeightInPoints((short) puntos);
            f.setBold(negrita);
            f.setColor(color);
            return f;
        }

        private static XSSFColor color(int r, int g, int b) {
            return new XSSFColor(new byte[]{(byte) r, (byte) g, (byte) b}, null);
        }
    }
}
