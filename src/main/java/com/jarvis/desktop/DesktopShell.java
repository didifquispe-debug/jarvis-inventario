/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.desktop;

import com.jarvis.security.AccessToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class DesktopShell {

    private final ConfigurableApplicationContext context;
    private final boolean enabled;
    private final String url;
    private final AccessToken token;
    private TrayIcon trayIcon;

    public DesktopShell(ConfigurableApplicationContext context,
                        @Value("${jarvis.desktop.enabled:true}") boolean enabled,
                        @Value("${server.port:8081}") int port,
                        AccessToken token,
                        @Value("${jarvis.browser:chrome}") String browser) {
        this.context = context;
        this.enabled = enabled;
        this.token = token;
        this.url = localUrl(port, token.value());
        AppWindow.setPreferred(browser);
    }

    public static String localUrl(int port, String token) {
        return "http://localhost:" + port + "/#t=" + URLEncoder.encode(token, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled || GraphicsEnvironment.isHeadless()) return;
        installTrayIcon();
        AppWindow.open(url);
    }

    private void installTrayIcon() {
        if (!SystemTray.isSupported()) return;
        PopupMenu menu = new PopupMenu();

        MenuItem abrir = new MenuItem("Abrir JARVIS");
        abrir.addActionListener(e -> AppWindow.open(url));
        menu.add(abrir);
        menu.addSeparator();

        MenuItem salir = new MenuItem("Salir");
        salir.addActionListener(e -> shutdown());
        menu.add(salir);

        trayIcon = new TrayIcon(drawIcon(), "J.A.R.V.I.S. - en linea", menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(e -> AppWindow.open(url));
        try {
            SystemTray.getSystemTray().add(trayIcon);
            if (token.generada()) {
                trayIcon.displayMessage("J.A.R.V.I.S. - clave nueva",
                        "Clave para el celular: " + token.value(), TrayIcon.MessageType.WARNING);
            } else {
                trayIcon.displayMessage("J.A.R.V.I.S.", "Sistemas en linea.", TrayIcon.MessageType.INFO);
            }
        } catch (AWTException e) {
            System.err.println("No pude crear el icono de bandeja: " + e.getMessage());
        }
    }

    private void shutdown() {
        if (trayIcon != null) SystemTray.getSystemTray().remove(trayIcon);
        int code = SpringApplication.exit(context, () -> 0);
        System.exit(code);
    }

    private static Image drawIcon() {
        int s = 64;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(5, 8, 15));
        g.fillOval(0, 0, s, s);
        g.setColor(new Color(63, 208, 255));
        g.setStroke(new BasicStroke(5));
        g.drawOval(6, 6, s - 12, s - 12);
        g.fillOval(20, 20, 24, 24);
        g.setColor(Color.WHITE);
        g.fillOval(27, 27, 10, 10);
        g.dispose();
        return img;
    }
}
