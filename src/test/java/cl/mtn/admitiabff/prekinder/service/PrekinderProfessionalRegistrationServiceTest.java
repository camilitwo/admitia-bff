package cl.mtn.admitiabff.prekinder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.util.JsonSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class PrekinderProfessionalRegistrationServiceTest {
    private final NamedParameterJdbcTemplate prekinderJdbc = mock(NamedParameterJdbcTemplate.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JsonSupport jsonSupport = mock(JsonSupport.class);
    private PrekinderProfessionalRegistrationService service;

    @BeforeEach
    void setUp() {
        service = new PrekinderProfessionalRegistrationService(
            prekinderJdbc, userRepository, passwordEncoder, jsonSupport);
        when(jsonSupport.write(any())).thenReturn("{\"prekinderProfessional\":true}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void activePrekinderProfessionalCanRegisterWithEmailAndPassword() {
        var candidate = new PrekinderProfessionalRegistrationService.ProfessionalCandidate(
            UUID.randomUUID(), "Ana Pérez Soto");
        when(prekinderJdbc.query(anyString(), any(java.util.Map.class), any(RowMapper.class)))
            .thenReturn(List.of(candidate));
        when(userRepository.existsByEmailIgnoreCase("ana@mtn.cl")).thenReturn(false);
        when(passwordEncoder.encode("secreta1")).thenReturn("bcrypt");
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(73L);
            return user;
        });

        var result = service.register(" ANA@MTN.CL ", "secreta1");

        assertThat(result.userId()).isEqualTo(73L);
        assertThat(result.email()).isEqualTo("ana@mtn.cl");
        ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getFirstName()).isEqualTo("Ana");
        assertThat(saved.getValue().getLastName()).isEqualTo("Pérez Soto");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.PREKINDER_PROFESSIONAL);
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("bcrypt");
        assertThat(saved.getValue().isActive()).isTrue();
        assertThat(saved.getValue().isEmailVerified()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownOrInactiveProfessionalCannotRegister() {
        when(prekinderJdbc.query(anyString(), any(java.util.Map.class), any(RowMapper.class)))
            .thenReturn(List.of());

        assertThatThrownBy(() -> service.register("nadie@mtn.cl", "secreta1"))
            .isInstanceOf(PrekinderDomainException.class)
            .extracting("code").isEqualTo("PROFESSIONAL_NOT_FOUND");
    }

    @Test
    @SuppressWarnings("unchecked")
    void professionalRegistrationCannotReplaceAnExistingAccount() {
        var candidate = new PrekinderProfessionalRegistrationService.ProfessionalCandidate(
            UUID.randomUUID(), "Ana Pérez");
        when(prekinderJdbc.query(anyString(), any(java.util.Map.class), any(RowMapper.class)))
            .thenReturn(List.of(candidate));
        when(userRepository.existsByEmailIgnoreCase(eq("ana@mtn.cl"))).thenReturn(true);

        assertThatThrownBy(() -> service.register("ana@mtn.cl", "secreta1"))
            .isInstanceOf(PrekinderDomainException.class)
            .extracting("code").isEqualTo("PROFESSIONAL_ALREADY_REGISTERED");
    }
}
