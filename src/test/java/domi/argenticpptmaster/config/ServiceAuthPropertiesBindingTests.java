package domi.argenticpptmaster.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ServiceAuthPropertiesBindingTests {

    @Test
    void bindsEnabledClientsFromProperties() {
        ConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "ppt-master.service-auth.enabled", "true",
                "ppt-master.service-auth.clients[0].id", "studio",
                "ppt-master.service-auth.clients[0].key", "s-key",
                "ppt-master.service-auth.clients[1].id", "script",
                "ppt-master.service-auth.clients[1].key", "p-key"));

        ServiceAuthProperties bound = new Binder(source)
                .bind("ppt-master.service-auth", Bindable.of(ServiceAuthProperties.class))
                .get();

        assertThat(bound.enabled()).isTrue();
        assertThat(bound.clients()).hasSize(2);
        assertThat(bound.clients().get(0).id()).isEqualTo("studio");
        assertThat(bound.clients().get(0).key()).isEqualTo("s-key");
        assertThat(bound.clients().get(1).id()).isEqualTo("script");
    }

    @Test
    void dropsBlankClientEntries() {
        ServiceAuthProperties properties = new ServiceAuthProperties(
                true,
                java.util.List.of(
                        new ServiceAuthProperties.Client("studio", "ok"),
                        new ServiceAuthProperties.Client(" ", "x"),
                        new ServiceAuthProperties.Client("script", "")));

        assertThat(properties.clients()).containsExactly(new ServiceAuthProperties.Client("studio", "ok"));
    }
}
