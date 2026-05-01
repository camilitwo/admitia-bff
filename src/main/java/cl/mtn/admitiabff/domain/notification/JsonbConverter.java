package cl.mtn.admitiabff.domain.notification;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.postgresql.util.PGobject;

import java.sql.SQLException;

/**
 * JPA Converter for PostgreSQL jsonb columns.
 * Converts between String (Java) and PGobject/jsonb (PostgreSQL).
 */
@Converter
public class JsonbConverter implements AttributeConverter<String, PGobject> {

    @Override
    public PGobject convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            PGobject pgObject = new PGobject();
            pgObject.setType("jsonb");
            pgObject.setValue(attribute);
            return pgObject;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to convert String to jsonb PGobject", e);
        }
    }

    @Override
    public String convertToEntityAttribute(PGobject dbData) {
        if (dbData == null) {
            return null;
        }
        return dbData.getValue();
    }
}
