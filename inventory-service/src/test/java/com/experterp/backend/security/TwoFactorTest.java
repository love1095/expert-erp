package com.experterp.backend.security;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.controller.AuthController;
import com.experterp.backend.dto.LoginRequest;
import com.experterp.backend.dto.LoginResponse;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.entity.User;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.TenantRepository;
import com.experterp.backend.repository.UserRepository;
import com.experterp.backend.service.TwoFactorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase I5: two-step sign-in with an authenticator app, like Cin7's two-factor authentication. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TwoFactorTest {

    @Autowired AuthController auth;
    @Autowired TwoFactorService twoFactor;
    @Autowired JwtUtil jwtUtil;
    @Autowired SecretCipher cipher;
    @Autowired UserDetailsServiceImpl userDetailsService;
    @Autowired UserRepository userRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired PasswordEncoder passwordEncoder;

    Tenant tenant;
    String id;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID().toString().substring(0, 8);
        tenant = new Tenant();
        tenant.setName("2FA " + id);
        tenant.setSlug("twofa-" + id);
        tenant = tenantRepository.save(tenant);
        TenantContext.set(tenant.getId());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void withTheAppOnThePasswordAloneGivesNoSessionTheCodeDoes() {
        User paula = user("paula-" + id);
        assertThat(body(login(paula))).containsKey("token");                     // off: password is enough

        TwoFactorService.Setup setup = twoFactor.start(paula);
        assertThat(setup.otpauthUri()).startsWith("otpauth://totp/Expert%20ERP:").contains("secret=" + setup.secret());
        assertThatThrownBy(() -> twoFactor.confirm(reload(paula), "000000")).hasMessageContaining("not right");
        List<String> recovery = twoFactor.confirm(reload(paula), now(setup.secret(), -1));
        assertThat(recovery).hasSize(8).allMatch(c -> c.matches("[a-z2-9]{4}-[a-z2-9]{4}"));

        // The password now gives a challenge, which is not a session
        Map<String, Object> first = body(login(paula));
        assertThat(first).containsEntry("twoFactor", "CODE").doesNotContainKey("token");
        String challenge = (String) first.get("challenge");
        assertThat(jwtUtil.isTokenValid(challenge, userDetailsService.loadUserByUsername(paula.getUsername()))).isFalse();
        assertThat(jwtUtil.challengeUsername(challenge, "SETUP")).isNull();

        // Wrong code: refused; the right one: a session; the same code again: refused
        assertThat(auth.loginTwoFactor(new AuthController.TwoFactorLogin(challenge, "123456")).getStatusCode().value()).isEqualTo(401);
        String code = now(setup.secret(), 0);
        ResponseEntity<?> ok = auth.loginTwoFactor(new AuthController.TwoFactorLogin(challenge, code));
        assertThat(ok.getBody()).isInstanceOf(LoginResponse.class);
        assertThat(jwtUtil.isTokenValid(((LoginResponse) ok.getBody()).getToken(),
                userDetailsService.loadUserByUsername(paula.getUsername()))).isTrue();
        assertThat(body(auth.loginTwoFactor(new AuthController.TwoFactorLogin(challenge, code)))).containsEntry("error", "That code was used already: wait for the next one");

        // A recovery code works once
        assertThat(auth.loginTwoFactor(new AuthController.TwoFactorLogin(challenge, recovery.get(0).toUpperCase())).getStatusCode().value()).isEqualTo(200);
        assertThat(auth.loginTwoFactor(new AuthController.TwoFactorLogin(challenge, recovery.get(0))).getStatusCode().value()).isEqualTo(401);
        assertThat(twoFactor.status(reload(paula)).recoveryCodesLeft()).isEqualTo(7);

        // 5 wrong codes in a row (the reused code and recovery code above count too) lock the second step for 15 minutes
        for (int i = 0; i < 3; i++) auth.loginTwoFactor(new AuthController.TwoFactorLogin(challenge, "000000"));
        assertThat(body(auth.loginTwoFactor(new AuthController.TwoFactorLogin(challenge, "000001")))).containsEntry("error", "That code is not right");
        assertThat((String) body(auth.loginTwoFactor(new AuthController.TwoFactorLogin(challenge, now(setup.secret(), 1)))).get("error"))
                .startsWith("Too many wrong codes");

        // The admin resets it (lost phone): the password is enough again
        signInAdmin();
        twoFactor.reset(paula.getId());
        assertThat(body(login(reload(paula)))).containsKey("token");
    }

    @Test
    void whenTheOrganisationRequiresItEveryoneSetsItUpAtSignIn() {
        User stephen = user("stephen-" + id);
        signInAdmin();
        twoFactor.setRequired(true);
        SecurityContextHolder.clearContext();

        Map<String, Object> first = body(login(stephen));
        assertThat(first).containsEntry("twoFactor", "SETUP").doesNotContainKey("token").containsKeys("secret", "otpauthUri");
        String challenge = (String) first.get("challenge");
        assertThat(auth.loginTwoFactor(new AuthController.TwoFactorLogin(challenge, "123456")).getStatusCode().value()).isEqualTo(401); // wrong purpose
        Map<String, Object> done = body(auth.loginTwoFactorSetup(new AuthController.TwoFactorLogin(challenge, now((String) first.get("secret"), 0))));
        assertThat(done).containsKeys("session", "recoveryCodes");
        assertThat(reload(stephen).isTotpEnabled()).isTrue();
        assertThat(cipher.decrypt(reload(stephen).getTotpSecret())).isEqualTo(first.get("secret"));   // kept encrypted
        assertThat(reload(stephen).getTotpSecret()).doesNotContain((String) first.get("secret"));

        // Cannot be turned off while required
        assertThatThrownBy(() -> twoFactor.disable(reload(stephen), now((String) first.get("secret"), 1)))
                .hasMessageContaining("requires two-step sign-in");
    }

    private User user(String name) {
        User u = new User();
        u.setUsername(name);
        u.setEmail(name + "@example.test");
        u.setPasswordHash(passwordEncoder.encode("Secret-pw-1"));
        u.setRole(User.Role.STAFF);
        u.setTenantId(tenant.getId());
        return userRepository.save(u);
    }

    private User reload(User u) {
        return userRepository.findById(u.getId()).orElseThrow();
    }

    private ResponseEntity<?> login(User u) {
        LoginRequest req = new LoginRequest();
        req.setUsername(u.getUsername());
        req.setPassword("Secret-pw-1");
        return auth.login(req);
    }

    private static String now(String secret, int offset) {
        return Totp.code(secret, Totp.stepAt(Instant.now().getEpochSecond()) + offset);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> r) {
        Object b = r.getBody();
        if (b instanceof LoginResponse l) return Map.of("token", l.getToken());
        return (Map<String, Object>) b;
    }

    private void signInAdmin() {
        TenantContext.set(tenant.getId());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
}
