package domi.argenticpptmaster.security;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.config.ServiceAuthProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServiceKeyAuthenticatorTests {

    @Test
    void authenticatesMatchingKeyToClientId() {
        ServiceKeyAuthenticator authenticator = new ServiceKeyAuthenticator(new ServiceAuthProperties(
                true,
                List.of(
                        new ServiceAuthProperties.Client("studio", "studio-secret"),
                        new ServiceAuthProperties.Client("script", "script-secret"))));

        assertThat(authenticator.authenticate("studio-secret")).contains("studio");
        assertThat(authenticator.authenticate("script-secret")).contains("script");
    }

    @Test
    void rejectsMissingAndInvalidKeys() {
        ServiceKeyAuthenticator authenticator = new ServiceKeyAuthenticator(new ServiceAuthProperties(
                true,
                List.of(new ServiceAuthProperties.Client("studio", "studio-secret"))));

        assertThat(authenticator.authenticate(null)).isEmpty();
        assertThat(authenticator.authenticate("")).isEmpty();
        assertThat(authenticator.authenticate("wrong")).isEmpty();
        assertThat(authenticator.authenticate("studio-secretx")).isEmpty();
    }
}
