/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import com.jarvis.security.ClientSession;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.Scope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Alcance "tienda": quien usa el DYSAInventoryService (controladores, voz, asistente) recibe el de la tienda del
 * pedido en curso (ClientSession.tienda()). Así cada tienda trabaja con su propia base sin cambiar a nadie más.
 */
public class TiendaScope implements Scope {

    public static final String NOMBRE = "tienda";

    private final ConfigurableListableBeanFactory fabrica;
    private volatile Tiendas tiendas;

    TiendaScope(ConfigurableListableBeanFactory fabrica) { this.fabrica = fabrica; }

    @Override
    public Object get(String name, ObjectFactory<?> objectFactory) {
        Tiendas t = tiendas;
        if (t == null) tiendas = t = fabrica.getBean(Tiendas.class);
        return t.actual();
    }

    @Override public Object remove(String name) { return null; }

    @Override public void registerDestructionCallback(String name, Runnable callback) {}

    @Override public Object resolveContextualObject(String key) { return null; }

    @Override public String getConversationId() { return "tienda-" + ClientSession.tienda(); }

    @Configuration
    static class Registro {
        @Bean
        static BeanFactoryPostProcessor alcanceTienda() {
            return fabrica -> fabrica.registerScope(NOMBRE, new TiendaScope(fabrica));
        }
    }
}
