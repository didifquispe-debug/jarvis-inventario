/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.security;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

/** Quita costos y ganancias de toda respuesta de la API cuando el usuario no tiene permiso de verlos (por ejemplo, el cajero). */
@Configuration
public class OcultarCostos {

    static final Set<String> CAMPOS = Set.of("costo", "costoUnitario", "valorCosto", "ganancia", "margen", "sinCosto");

    @Bean
    public Module moduloOcultarCostos() {
        SimpleModule m = new SimpleModule("jarvis-ocultar-costos");
        m.setSerializerModifier(new BeanSerializerModifier() {
            @Override
            public List<BeanPropertyWriter> changeProperties(SerializationConfig config, BeanDescription desc, List<BeanPropertyWriter> props) {
                props.replaceAll(p -> CAMPOS.contains(p.getName()) ? new Oculto(p) : p);
                return props;
            }
        });
        return m;
    }

    static final class Oculto extends BeanPropertyWriter {
        Oculto(BeanPropertyWriter base) { super(base); }

        @Override
        public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            if (ClientSession.puede(Rol.Permiso.VER_COSTOS)) super.serializeAsField(bean, gen, prov);
        }
    }
}
