package cl.mtn.admitiabff.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.person.ParentEntity;
import cl.mtn.admitiabff.domain.student.StudentEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.InterviewRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmailRecipientResolverTest {
    @Mock ApplicationRepository applicationRepository;
    @Mock InterviewRepository interviewRepository;

    @Test
    void resolvesStudentAndParentNamesForInstitutionalTemplates() {
        ApplicationEntity application = new ApplicationEntity();
        application.setId(40L);
        StudentEntity student = new StudentEntity();
        student.setFirstName("Alonso");
        student.setPaternalLastName("González");
        student.setMaternalLastName("Pérez");
        application.setStudent(student);
        ParentEntity father = new ParentEntity();
        father.setFullName("Jorge González");
        ParentEntity mother = new ParentEntity();
        mother.setFullName("María Pérez");
        application.setFather(father);
        application.setMother(mother);
        when(applicationRepository.findActiveById(40L)).thenReturn(Optional.of(application));

        EmailRecipientResolver resolver = new EmailRecipientResolver(applicationRepository, interviewRepository);
        Map<String, Object> context = resolver.resolveContextForApplication(40L);

        assertThat(context).containsEntry("applicationId", 40L);
        assertThat(context).containsEntry("studentName", "Alonso González Pérez");
        assertThat(context).containsEntry("parentNames", "Jorge González y María Pérez");
    }
}
