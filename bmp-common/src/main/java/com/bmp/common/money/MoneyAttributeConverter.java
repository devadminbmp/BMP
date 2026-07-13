package com.bmp.common.money;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Maps Money <-> BIGINT paise column. Apply with @Convert on entity fields. */
@Converter(autoApply = true)
public class MoneyAttributeConverter implements AttributeConverter<Money, Long> {

    @Override
    public Long convertToDatabaseColumn(Money attribute) {
        return attribute == null ? null : attribute.paise();
    }

    @Override
    public Money convertToEntityAttribute(Long dbData) {
        return dbData == null ? null : Money.ofPaise(dbData);
    }
}
