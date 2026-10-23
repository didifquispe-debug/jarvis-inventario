package com.jarvis.inventory;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Representación impresa (A4) de una boleta o nota de venta. Adaptada del ComprobantePdfService del sistema de
 * referencia: cabecera con la caja de RUC, tipo y número; datos del cliente; detalle; base, IGV y total; importe en
 * letras; marca de agua "ANULADO". Diferencias: columna de unidad (metros, kilos…) y "CLIENTES VARIOS" sin cliente.
 */
public final class ComprobantePdf {

    /** Datos del negocio que emite (jarvis.negocio.*). Los vacíos no se imprimen. */
    public record Emisor(String nombre, String ruc, String direccion, String telefono, String email) {}

    private static final Color ACENTO = new Color(42, 120, 214);
    private static final Color ACENTO_SUAVE = new Color(234, 242, 252);
    private static final Color TEXTO = new Color(31, 35, 40);
    private static final Color TEXTO_SUAVE = new Color(101, 109, 118);
    private static final Color BORDE = new Color(216, 222, 228);
    private static final Color FILA_ALTERNA = new Color(246, 248, 250);
    private static final Color PELIGRO = new Color(207, 34, 46);
    private static final DecimalFormat IMPORTE = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));

    private static final Font F_NORMAL = fuente(9, Font.NORMAL, TEXTO);
    private static final Font F_NEGRITA = fuente(9, Font.BOLD, TEXTO);
    private static final Font F_SUAVE = fuente(8.5f, Font.NORMAL, TEXTO_SUAVE);
    private static final Font F_ETIQUETA = fuente(7.5f, Font.BOLD, TEXTO_SUAVE);
    private static final Font F_CABECERA = fuente(8, Font.BOLD, Color.WHITE);
    private static final Font F_CAJA_RUC = fuente(11, Font.BOLD, TEXTO);
    private static final Font F_CAJA_TIPO = fuente(10.5f, Font.BOLD, Color.WHITE);
    private static final Font F_CAJA_NUMERO = fuente(13, Font.BOLD, ACENTO);
    private static final Font F_TOTAL = fuente(11, Font.BOLD, Color.WHITE);

    private ComprobantePdf() {}

    public static byte[] generar(InventoryService.Comprobante c, Emisor emisor) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 40, 50);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, salida);
            writer.setPageEvent(new PiePagina(c));
            doc.addTitle(c.tipoDescripcion + " " + c.codigo);
            doc.addAuthor(nz(emisor.nombre()));
            doc.open();
            doc.add(cabecera(c, emisor));
            doc.add(espacio(14));
            doc.add(datosCliente(c));
            doc.add(espacio(14));
            doc.add(tablaItems(c));
            doc.add(espacio(10));
            doc.add(totales(c));
            doc.add(espacio(12));
            doc.add(importeEnLetras(c));
        } catch (DocumentException e) {
            throw new IllegalStateException("No se pudo generar el PDF: " + e.getMessage(), e);
        } finally {
            doc.close();
        }
        return salida.toByteArray();
    }

    // ------------------------------------------------------------- secciones

    private static PdfPTable cabecera(InventoryService.Comprobante c, Emisor e) {
        PdfPTable tabla = new PdfPTable(new float[]{62, 38});
        tabla.setWidthPercentage(100);
        PdfPCell datos = sinBorde();
        datos.setVerticalAlignment(Element.ALIGN_MIDDLE);
        datos.addElement(new Paragraph(nz(e.nombre()).isBlank() ? "Mi Negocio" : e.nombre(), fuente(20, Font.BOLD, ACENTO)));
        if (!nz(e.direccion()).isBlank()) datos.addElement(new Paragraph(e.direccion(), F_SUAVE));
        StringBuilder contacto = new StringBuilder();
        if (!nz(e.telefono()).isBlank()) contacto.append("Telf. ").append(e.telefono());
        if (!nz(e.email()).isBlank()) contacto.append(contacto.isEmpty() ? "" : "  ·  ").append(e.email());
        if (!contacto.isEmpty()) datos.addElement(new Paragraph(contacto.toString(), F_SUAVE));
        tabla.addCell(datos);

        PdfPTable caja = new PdfPTable(1);
        caja.setWidthPercentage(100);
        if (!nz(e.ruc()).isBlank()) caja.addCell(celdaCaja(new Phrase("R.U.C. " + e.ruc(), F_CAJA_RUC), Color.WHITE, 10, 4));
        caja.addCell(celdaCaja(new Phrase(c.tipoDescripcion.toUpperCase(), F_CAJA_TIPO), ACENTO, 7, 7));
        caja.addCell(celdaCaja(new Phrase(c.codigo, F_CAJA_NUMERO), Color.WHITE, 6, 10));
        PdfPCell contenedor = new PdfPCell(caja);
        contenedor.setBorderColor(ACENTO);
        contenedor.setBorderWidth(1.2f);
        contenedor.setPadding(0);
        tabla.addCell(contenedor);
        return tabla;
    }

    private static PdfPTable datosCliente(InventoryService.Comprobante c) {
        PdfPTable tabla = new PdfPTable(new float[]{17, 43, 17, 23});
        tabla.setWidthPercentage(100);
        InventoryService.ClienteResumen cl = c.cliente;
        dato(tabla, "CLIENTE", cl == null ? "CLIENTES VARIOS" : cl.nombre().toUpperCase());
        dato(tabla, "FECHA DE EMISIÓN", fecha(c.fecha));
        dato(tabla, cl == null ? "DNI" : cl.tipoDocumento(), cl == null ? "—" : cl.documento());
        dato(tabla, "HORA", nz(c.hora));
        dato(tabla, "MONEDA", "SOLES (PEN)");
        dato(tabla, "CONDICIÓN", "CONTADO");
        PdfPTable marco = new PdfPTable(1);
        marco.setWidthPercentage(100);
        PdfPCell celda = new PdfPCell(tabla);
        celda.setBorderColor(BORDE);
        celda.setPadding(8);
        celda.setBackgroundColor(FILA_ALTERNA);
        marco.addCell(celda);
        return marco;
    }

    private static PdfPTable tablaItems(InventoryService.Comprobante c) {
        PdfPTable tabla = new PdfPTable(new float[]{7, 10, 12, 43, 13, 15});
        tabla.setWidthPercentage(100);
        tabla.setHeaderRows(1);
        String[] titulos = {"ÍTEM", "CANT.", "UNIDAD", "DESCRIPCIÓN", "P. UNIT.", "IMPORTE"};
        int[] alineacion = {Element.ALIGN_CENTER, Element.ALIGN_CENTER, Element.ALIGN_LEFT, Element.ALIGN_LEFT,
                Element.ALIGN_RIGHT, Element.ALIGN_RIGHT};
        for (int i = 0; i < titulos.length; i++) {
            PdfPCell t = new PdfPCell(new Phrase(titulos[i], F_CABECERA));
            t.setBackgroundColor(ACENTO);
            t.setBorder(Rectangle.NO_BORDER);
            t.setHorizontalAlignment(alineacion[i]);
            t.setPadding(6);
            t.setPaddingTop(7);
            t.setPaddingBottom(7);
            tabla.addCell(t);
        }
        int fila = 0;
        for (InventoryService.Venta v : c.items) {
            Color fondo = fila++ % 2 == 0 ? Color.WHITE : FILA_ALTERNA;
            tabla.addCell(celdaItem(String.valueOf(fila), Element.ALIGN_CENTER, fondo, F_NORMAL));
            tabla.addCell(celdaItem(Num.txt(v.cantidad), Element.ALIGN_CENTER, fondo, F_NORMAL));
            tabla.addCell(celdaItem(nz(v.unidad), Element.ALIGN_LEFT, fondo, F_SUAVE));
            tabla.addCell(celdaItem(v.producto, Element.ALIGN_LEFT, fondo, F_NORMAL));
            tabla.addCell(celdaItem(IMPORTE.format(v.precioUnitario), Element.ALIGN_RIGHT, fondo, F_NORMAL));
            tabla.addCell(celdaItem(IMPORTE.format(v.monto), Element.ALIGN_RIGHT, fondo, F_NEGRITA));
        }
        return tabla;
    }

    private static PdfPTable totales(InventoryService.Comprobante c) {
        PdfPTable externo = new PdfPTable(new float[]{55, 45});
        externo.setWidthPercentage(100);
        PdfPCell nota = sinBorde();
        nota.addElement(new Paragraph(c.leyendaIgv, F_SUAVE));
        nota.addElement(new Paragraph("Productos: " + c.items.size(), F_SUAVE));
        externo.addCell(nota);

        PdfPTable t = new PdfPTable(new float[]{60, 40});
        t.setWidthPercentage(100);
        boolean conIgv = c.tasaIgv.signum() > 0;
        filaTotal(t, conIgv ? "OP. GRAVADA" : "OP. EXONERADA", "S/ " + IMPORTE.format(c.base), false);
        String tasa = c.tasaIgv.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString();
        filaTotal(t, "IGV (" + tasa + "%)", "S/ " + IMPORTE.format(c.igv), false);
        filaTotal(t, "IMPORTE TOTAL", "S/ " + IMPORTE.format(c.total), true);
        PdfPCell celda = new PdfPCell(t);
        celda.setBorder(Rectangle.NO_BORDER);
        celda.setPadding(0);
        externo.addCell(celda);
        return externo;
    }

    private static PdfPTable importeEnLetras(InventoryService.Comprobante c) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        Phrase frase = new Phrase();
        frase.add(new Chunk("SON: ", F_ETIQUETA));
        frase.add(new Chunk(c.totalEnLetras, F_NEGRITA));
        PdfPCell celda = new PdfPCell(frase);
        celda.setBorderColor(BORDE);
        celda.setBackgroundColor(ACENTO_SUAVE);
        celda.setPadding(8);
        t.addCell(celda);
        return t;
    }

    // ---------------------------------------------------------------- celdas

    private static void dato(PdfPTable tabla, String etiqueta, String valor) {
        PdfPCell e = sinBorde();
        e.setPhrase(new Phrase(etiqueta, F_ETIQUETA));
        e.setPaddingBottom(5);
        tabla.addCell(e);
        PdfPCell v = sinBorde();
        v.setPhrase(new Phrase(valor, F_NORMAL));
        v.setPaddingBottom(5);
        tabla.addCell(v);
    }

    private static PdfPCell celdaCaja(Phrase texto, Color fondo, float arriba, float abajo) {
        PdfPCell c = new PdfPCell(texto);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setBorder(Rectangle.NO_BORDER);
        c.setBackgroundColor(fondo);
        c.setPaddingTop(arriba);
        c.setPaddingBottom(abajo);
        return c;
    }

    private static PdfPCell celdaItem(String texto, int alineacion, Color fondo, Font fuente) {
        PdfPCell c = new PdfPCell(new Phrase(texto, fuente));
        c.setHorizontalAlignment(alineacion);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setBackgroundColor(fondo);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(BORDE);
        c.setPadding(6);
        c.setPaddingBottom(7);
        return c;
    }

    private static void filaTotal(PdfPTable t, String etiqueta, String valor, boolean destacado) {
        PdfPCell e = new PdfPCell(new Phrase(etiqueta, destacado ? F_TOTAL : F_NEGRITA));
        PdfPCell v = new PdfPCell(new Phrase(valor, destacado ? F_TOTAL : F_NORMAL));
        for (PdfPCell c : new PdfPCell[]{e, v}) {
            c.setBorder(Rectangle.BOTTOM);
            c.setBorderColor(BORDE);
            c.setPadding(6);
            if (destacado) {
                c.setBackgroundColor(ACENTO);
                c.setBorder(Rectangle.NO_BORDER);
                c.setPaddingTop(8);
                c.setPaddingBottom(9);
            }
        }
        v.setHorizontalAlignment(Element.ALIGN_RIGHT);
        t.addCell(e);
        t.addCell(v);
    }

    private static PdfPCell sinBorde() {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(0);
        return c;
    }

    private static Paragraph espacio(float alto) {
        Paragraph p = new Paragraph(" ");
        p.setLeading(alto);
        return p;
    }

    private static Font fuente(float tamanio, int estilo, Color color) {
        return new Font(Font.HELVETICA, tamanio, estilo, color);
    }

    /** "2026-10-23" → "23/10/2026". */
    private static String fecha(String iso) {
        if (iso == null || !iso.matches("\\d{4}-\\d{2}-\\d{2}")) return nz(iso);
        return iso.substring(8, 10) + "/" + iso.substring(5, 7) + "/" + iso.substring(0, 4);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Pie con la leyenda y el número de página; marca de agua si el comprobante está anulado. */
    private static final class PiePagina extends PdfPageEventHelper {
        private final InventoryService.Comprobante c;

        PiePagina(InventoryService.Comprobante c) { this.c = c; }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            float centro = (document.left() + document.right()) / 2;
            float y = document.bottom() - 22;
            cb.setColorStroke(BORDE);
            cb.setLineWidth(0.6f);
            cb.moveTo(document.left(), y + 12);
            cb.lineTo(document.right(), y + 12);
            cb.stroke();
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER,
                    new Phrase("Representación impresa de la " + c.tipoDescripcion + "  ·  Gracias por su compra", F_SUAVE), centro, y, 0);
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT, new Phrase("Pág. " + writer.getPageNumber(), F_SUAVE),
                    document.right(), y, 0);
            if (!c.anulado) return;
            PdfContentByte debajo = writer.getDirectContentUnder();
            debajo.saveState();
            PdfGState estado = new PdfGState();
            estado.setFillOpacity(0.12f);
            debajo.setGState(estado);
            try {
                debajo.beginText();
                debajo.setFontAndSize(BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, false), 110);
                debajo.setColorFill(PELIGRO);
                debajo.showTextAligned(Element.ALIGN_CENTER, "ANULADO", centro, (document.top() + document.bottom()) / 2, 40);
                debajo.endText();
            } catch (DocumentException | IOException e) {
                throw new IllegalStateException(e);
            }
            debajo.restoreState();
        }
    }
}
