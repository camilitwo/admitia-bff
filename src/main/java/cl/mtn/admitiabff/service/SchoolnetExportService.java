package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.ApplicationStatus;
import cl.mtn.admitiabff.domain.person.GuardianEntity;
import cl.mtn.admitiabff.domain.person.ParentEntity;
import cl.mtn.admitiabff.domain.person.SupporterEntity;
import cl.mtn.admitiabff.domain.student.StudentEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SchoolnetExportService {
    static final List<String> HEADERS = List.of(
        "[Alumnos]Nombres",
        "[Alumnos]Apellido_paterno",
        "[Alumnos]Apellido_materno",
        "[Alumnos]Identificador Nacional",
        "[Alumnos]Fecha_de_nacimiento",
        "[Alumnos]Nacionalidad",
        "[Alumnos]direccion",
        "[Alumnos]comuna",
        "[Alumnos]telefono",
        "[Alumnos]Colegio_de_origen",
        "[Alumnos]nivel_al_que_ingreso",
        "[Alumnos]Sexo",
        "[Alumnos]Fecha_Matricula",
        "[Alumnos]Familia",
        "[Padre]Nombres",
        "[Padre]Apellido_paterno",
        "[Padre]Apellido_materno",
        "[Padre]Fecha_de_nacimiento",
        "[Padre]Identificador Nacional",
        "[Padre]Nacionalidad",
        "[Padre]Sexo",
        "[Padre]direccion",
        "[Padre]comuna",
        "[Padre]telefonoDomicilio",
        "[Padre]celular",
        "[Padre]eMail",
        "[Madre]Nombres",
        "[Madre]Apellido_paterno",
        "[Madre]Apellido_materno",
        "[Madre]Fecha_de_nacimiento",
        "[Madre]Identificador Nacional",
        "[Madre]Nacionalidad",
        "[Madre]Sexo",
        "[Madre]direccion",
        "[Madre]comuna",
        "[Madre]telefonoDomicilio",
        "[Madre]celular",
        "[Madre]eMail",
        "[Apoderado de cuenta]Nombres",
        "[Apoderado de cuenta]Apellido_paterno",
        "[Apoderado de cuenta]Apellido_materno",
        "[Apoderado de cuenta]Identificador Nacional",
        "[Apoderado académico]Nombres",
        "[Apoderado académico]Apellido_paterno",
        "[Apoderado académico]Apellido_materno",
        "[Apoderado académico]Identificador Nacional",
        "[Familia]Nombre_de_la_familia",
        "[Familia]direccion",
        "[Familia]comuna",
        "[Familia]telefono",
        "[Familia]añoIngreso",
        "[Familia]eMail"
    );

    private static final DateTimeFormatter SCHOOLNET_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final String DEFAULT_COUNTRY = "Chile";
    private static final String DEFAULT_ENROLLMENT_DATE = "00-00-0000";

    private final ApplicationRepository applicationRepository;

    public SchoolnetExportService(ApplicationRepository applicationRepository) {
        this.applicationRepository = applicationRepository;
    }

    public byte[] exportAcceptedStudents() {
        List<ApplicationEntity> applications = applicationRepository.findActiveForSchoolnetExport(ApplicationStatus.APPROVED);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Alumnos");
            writeHeader(sheet, workbook);
            int rowIndex = 1;
            for (ApplicationEntity application : applications) {
                writeApplication(sheet.createRow(rowIndex++), application);
            }
            for (int col = 0; col < HEADERS.size(); col++) {
                int width = Math.min(Math.max(HEADERS.get(col).length() + 2, 12), 35);
                sheet.setColumnWidth(col, width * 256);
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo generar la exportación SchoolNet", exception);
        }
    }

    private void writeHeader(Sheet sheet, XSSFWorkbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        Row row = sheet.createRow(0);
        for (int col = 0; col < HEADERS.size(); col++) {
            Cell cell = row.createCell(col);
            cell.setCellValue(HEADERS.get(col));
            cell.setCellStyle(style);
        }
    }

    private void writeApplication(Row row, ApplicationEntity application) {
        Map<String, String> values = toSchoolnetRow(application);
        for (int col = 0; col < HEADERS.size(); col++) {
            row.createCell(col).setCellValue(values.getOrDefault(HEADERS.get(col), ""));
        }
    }

    Map<String, String> toSchoolnetRow(ApplicationEntity application) {
        StudentEntity student = application.getStudent();
        Adult accountGuardian = firstAdult(application.getSupporter(), application.getFather(), application.getMother(), application.getGuardian());
        Adult academicGuardian = firstAdult(application.getGuardian(), application.getMother(), application.getFather());
        String studentAddress = value(student.getAddress());
        String studentComuna = value(student.getComuna());
        String familyPhone = firstValue(accountGuardian.phone(), academicGuardian.phone(), phone(application.getFather()), phone(application.getMother()), phone(application.getGuardian()));
        String familyEmail = firstValue(email(application.getMother()), email(application.getFather()), email(application.getGuardian()), applicantEmail(application.getApplicantUser()));

        Map<String, String> row = new LinkedHashMap<>();
        put(row, "[Alumnos]Nombres", student.getFirstName());
        put(row, "[Alumnos]Apellido_paterno", student.getPaternalLastName());
        put(row, "[Alumnos]Apellido_materno", student.getMaternalLastName());
        put(row, "[Alumnos]Identificador Nacional", normalizeRut(student.getRut()));
        put(row, "[Alumnos]Fecha_de_nacimiento", formatDate(student.getBirthDate()));
        put(row, "[Alumnos]Nacionalidad", firstValue(student.getPais(), DEFAULT_COUNTRY));
        put(row, "[Alumnos]direccion", studentAddress);
        put(row, "[Alumnos]comuna", studentComuna);
        put(row, "[Alumnos]telefono", familyPhone);
        put(row, "[Alumnos]Colegio_de_origen", student.getCurrentSchool());
        put(row, "[Alumnos]nivel_al_que_ingreso", schoolnetGrade(student.getGradeApplied()));
        put(row, "[Alumnos]Sexo", schoolnetGender(student.getGender()));
        put(row, "[Alumnos]Fecha_Matricula", DEFAULT_ENROLLMENT_DATE);
        put(row, "[Alumnos]Familia", familyName(student));
        putAdult(row, "[Padre]", application.getFather(), studentComuna, "M");
        putAdult(row, "[Madre]", application.getMother(), studentComuna, "F");
        putGuardian(row, "[Apoderado de cuenta]", accountGuardian);
        putGuardian(row, "[Apoderado académico]", academicGuardian);
        put(row, "[Familia]Nombre_de_la_familia", familyName(student));
        put(row, "[Familia]direccion", firstValue(studentAddress, accountGuardian.address(), academicGuardian.address()));
        put(row, "[Familia]comuna", studentComuna);
        put(row, "[Familia]telefono", familyPhone);
        put(row, "[Familia]añoIngreso", application.getAcademicYear() == null ? "0" : application.getAcademicYear().toString());
        put(row, "[Familia]eMail", familyEmail);
        return row;
    }

    private void putAdult(Map<String, String> row, String prefix, ParentEntity parent, String comuna, String sex) {
        Adult adult = adult(parent);
        put(row, prefix + "Nombres", adult.nameParts().names());
        put(row, prefix + "Apellido_paterno", adult.nameParts().paternalLastName());
        put(row, prefix + "Apellido_materno", adult.nameParts().maternalLastName());
        put(row, prefix + "Fecha_de_nacimiento", "");
        put(row, prefix + "Identificador Nacional", normalizeRut(adult.rut()));
        put(row, prefix + "Nacionalidad", adult.isEmpty() ? "" : DEFAULT_COUNTRY);
        put(row, prefix + "Sexo", adult.isEmpty() ? "" : sex);
        put(row, prefix + "direccion", adult.address());
        put(row, prefix + "comuna", comuna);
        put(row, prefix + "telefonoDomicilio", "");
        put(row, prefix + "celular", adult.phone());
        put(row, prefix + "eMail", adult.email());
    }

    private void putGuardian(Map<String, String> row, String prefix, Adult adult) {
        put(row, prefix + "Nombres", adult.nameParts().names());
        put(row, prefix + "Apellido_paterno", adult.nameParts().paternalLastName());
        put(row, prefix + "Apellido_materno", adult.nameParts().maternalLastName());
        put(row, prefix + "Identificador Nacional", normalizeRut(adult.rut()));
    }

    private static void put(Map<String, String> row, String key, String value) {
        row.put(key, value(value));
    }

    private static Adult firstAdult(SupporterEntity supporter, ParentEntity father, ParentEntity mother, GuardianEntity guardian) {
        return firstAdult(adult(supporter), adult(father), adult(mother), adult(guardian));
    }

    private static Adult firstAdult(GuardianEntity guardian, ParentEntity mother, ParentEntity father) {
        return firstAdult(adult(guardian), adult(mother), adult(father));
    }

    private static Adult firstAdult(Adult... adults) {
        for (Adult adult : adults) {
            if (!adult.isEmpty()) {
                return adult;
            }
        }
        return Adult.empty();
    }

    private static Adult adult(ParentEntity parent) {
        if (parent == null) return Adult.empty();
        return new Adult(parent.getFullName(), parent.getRut(), parent.getEmail(), parent.getPhone(), parent.getAddress());
    }

    private static Adult adult(GuardianEntity guardian) {
        if (guardian == null) return Adult.empty();
        return new Adult(guardian.getFullName(), guardian.getRut(), guardian.getEmail(), guardian.getPhone(), guardian.getAddress());
    }

    private static Adult adult(SupporterEntity supporter) {
        if (supporter == null) return Adult.empty();
        return new Adult(supporter.getFullName(), supporter.getRut(), supporter.getEmail(), supporter.getPhone(), supporter.getAddress());
    }

    private static String familyName(StudentEntity student) {
        return (value(student.getPaternalLastName()) + " " + value(student.getMaternalLastName())).trim();
    }

    static String normalizeRut(String rut) {
        return value(rut).replace(".", "").replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
    }

    static String formatDate(LocalDate date) {
        return date == null ? "" : SCHOOLNET_DATE.format(date);
    }

    static NameParts splitAdultName(String fullName) {
        String clean = value(fullName).replaceAll("\\s+", " ").trim();
        if (clean.isBlank()) {
            return new NameParts("", "", "");
        }
        String[] parts = clean.split(" ");
        if (parts.length >= 3) {
            return new NameParts(
                String.join(" ", java.util.Arrays.copyOfRange(parts, 0, parts.length - 2)),
                parts[parts.length - 2],
                parts[parts.length - 1]
            );
        }
        if (parts.length == 2) {
            return new NameParts(parts[0], parts[1], "");
        }
        return new NameParts(parts[0], "", "");
    }

    static String schoolnetGender(String gender) {
        String normalized = value(gender).trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("MALE") || normalized.equals("MASCULINO") || normalized.equals("M")) return "M";
        if (normalized.equals("FEMALE") || normalized.equals("FEMENINO") || normalized.equals("F")) return "F";
        return "";
    }

    static String schoolnetGrade(String grade) {
        String normalized = value(grade).trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "PRE_KINDER", "PREKINDER", "PK" -> "Pre Kinder";
            case "KINDER", "KINDERGARTEN", "K" -> "Kinder";
            case "1_BASICO", "PRIMERO_BASICO", "1 BASICO", "1 BÁSICO" -> "1o Básico";
            case "2_BASICO", "SEGUNDO_BASICO", "2 BASICO", "2 BÁSICO" -> "2o Básico";
            case "3_BASICO", "TERCERO_BASICO", "3 BASICO", "3 BÁSICO" -> "3o Básico";
            case "4_BASICO", "CUARTO_BASICO", "4 BASICO", "4 BÁSICO" -> "4o Básico";
            case "5_BASICO", "QUINTO_BASICO", "5 BASICO", "5 BÁSICO" -> "5o Básico";
            case "6_BASICO", "SEXTO_BASICO", "6 BASICO", "6 BÁSICO" -> "6o Básico";
            case "7_BASICO", "SEPTIMO_BASICO", "SÉPTIMO_BASICO", "7 BASICO", "7 BÁSICO" -> "7o Básico";
            case "8_BASICO", "OCTAVO_BASICO", "8 BASICO", "8 BÁSICO" -> "8o Básico";
            case "1_MEDIO", "PRIMERO_MEDIO", "1 MEDIO" -> "1o Medio";
            case "2_MEDIO", "SEGUNDO_MEDIO", "2 MEDIO" -> "2o Medio";
            case "3_MEDIO", "TERCERO_MEDIO", "3 MEDIO" -> "3o Medio";
            case "4_MEDIO", "CUARTO_MEDIO", "4 MEDIO" -> "4o Medio";
            default -> value(grade);
        };
    }

    private static String firstValue(String... values) {
        for (String value : values) {
            String clean = value(value);
            if (!clean.isBlank()) {
                return clean;
            }
        }
        return "";
    }

    private static String phone(ParentEntity parent) {
        return parent == null ? "" : parent.getPhone();
    }

    private static String phone(GuardianEntity guardian) {
        return guardian == null ? "" : guardian.getPhone();
    }

    private static String email(ParentEntity parent) {
        return parent == null ? "" : parent.getEmail();
    }

    private static String email(GuardianEntity guardian) {
        return guardian == null ? "" : guardian.getEmail();
    }

    private static String applicantEmail(UserEntity user) {
        return user == null ? "" : user.getEmail();
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    record NameParts(String names, String paternalLastName, String maternalLastName) { }

    record Adult(String fullName, String rut, String email, String phone, String address) {
        static Adult empty() {
            return new Adult("", "", "", "", "");
        }

        NameParts nameParts() {
            return splitAdultName(fullName);
        }

        boolean isEmpty() {
            return value(fullName).isBlank()
                && value(rut).isBlank()
                && value(email).isBlank()
                && value(phone).isBlank()
                && value(address).isBlank();
        }
    }
}
