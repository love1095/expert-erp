package com.experterp.backend.config;

import com.experterp.backend.security.Permission;
import com.experterp.backend.security.Restricted;
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

import java.util.ArrayList;
import java.util.List;

/** Sends {@link Restricted} fields as null to users without their permission (phase I1). */
@Configuration
public class RestrictedFieldsConfig {

    @Bean
    public Module restrictedFieldsModule() {
        SimpleModule module = new SimpleModule("restricted-fields");
        module.setSerializerModifier(new BeanSerializerModifier() {
            @Override
            public List<BeanPropertyWriter> changeProperties(SerializationConfig config, BeanDescription desc, List<BeanPropertyWriter> props) {
                List<BeanPropertyWriter> out = new ArrayList<>(props.size());
                for (BeanPropertyWriter w : props) {
                    Restricted r = w.getAnnotation(Restricted.class);
                    out.add(r == null ? w : new RestrictedWriter(w, r.value()));
                }
                return out;
            }
        });
        return module;
    }

    static final class RestrictedWriter extends BeanPropertyWriter {
        private final Permission permission;

        RestrictedWriter(BeanPropertyWriter base, Permission permission) {
            super(base);
            this.permission = permission;
        }

        @Override
        public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            if (Permission.current(permission)) {
                super.serializeAsField(bean, gen, prov);
            } else {
                gen.writeNullField(getName());
            }
        }
    }
}
