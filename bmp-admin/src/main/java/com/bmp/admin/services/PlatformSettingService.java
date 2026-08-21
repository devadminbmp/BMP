package com.bmp.admin.services;

import com.bmp.admin.dto.ConsoleDtos.*;
import com.bmp.admin.entities.PlatformSetting;
import com.bmp.admin.repositories.PlatformSettingRepository;
import com.bmp.admin.security.StaffPermission;
import com.bmp.admin.security.StaffPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Runtime platform configuration — feature flags and the kill switch.
 *
 * <h2>Why settings live in the database rather than in config</h2>
 * A config-server change needs a deploy or a bus refresh, and both take minutes and a person who
 * knows how. At 2am during an incident you want one button. {@code new_bookings_enabled} is that
 * button: it stops customers making new bookings platform-wide without touching a single
 * booking that already exists.
 *
 * <h2>Every change is audited, with a reason</h2>
 * A flag nobody can attribute is how a platform ends up in a state nobody admits to causing —
 * and the change most worth attributing is the one made under pressure at 2am.
 *
 * <h2>Reads never throw</h2>
 * A missing or corrupted setting falls back to the safe default rather than taking a service
 * down. A typo in a settings row should not be able to break bookings.
 */
@Service
public class PlatformSettingService {

    private static final Logger log = LoggerFactory.getLogger(PlatformSettingService.class);

    public static final String NEW_BOOKINGS_ENABLED = "new_bookings_enabled";
    public static final String SALON_AUTO_APPROVE = "salon_auto_approve";
    public static final String SUPPORT_FIRST_RESPONSE_HOURS = "support_first_response_hours";
    public static final String DATA_REQUEST_DUE_DAYS = "data_request_due_days";

    private final PlatformSettingRepository settings;
    private final AuditLogService audit;

    public PlatformSettingService(PlatformSettingRepository settings, AuditLogService audit) {
        this.settings = settings;
        this.audit = audit;
    }

    public List<SettingResponse> list() {
        return settings.findAllByOrderBySettingKeyAsc().stream()
                .map(s -> new SettingResponse(s.getSettingKey(), s.getSettingValue(),
                        s.getValueType(), s.getDescription()))
                .toList();
    }

    /** Safe default on anything unexpected — see the class comment. */
    public boolean flag(String key, boolean fallback) {
        return settings.findBySettingKey(key).map(PlatformSetting::asBoolean).orElse(fallback);
    }

    public long number(String key, long fallback) {
        return settings.findBySettingKey(key).map(s -> s.asLong(fallback)).orElse(fallback);
    }

    /**
     * Change a setting.
     *
     * <p>Ops and superadmin only, and a justification is required by the DTO's validation. The
     * kill switch in particular gets a WARN-level log line, because "when did new bookings stop
     * and who stopped them" is the first question asked in an incident review.
     */
    @Transactional
    public SettingResponse update(String key, SettingChangeRequest req, StaffPrincipal caller, String ip) {
        if (!caller.can(StaffPermission.SETTINGS_MANAGE)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your role cannot change platform settings.");
        }

        PlatformSetting setting = settings.findBySettingKey(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "UNKNOWN_SETTING: " + key));

        // Reject a value the type can't hold, rather than storing it and failing later at the
        // point of use — where the cause is much harder to find.
        validate(setting.getValueType(), req.value());

        String previous = setting.getSettingValue();
        setting.update(req.value(), caller.staffId());

        audit.record("bmp_staff", caller.staffId(), "SETTING_CHANGED", "platform_setting", setting.getId(),
                Map.of("key", key, "from", previous, "to", req.value()),
                ip, caller.email(), caller.role(), req.justification());

        if (NEW_BOOKINGS_ENABLED.equals(key)) {
            log.warn("KILL SWITCH: {} changed {} -> {} by {} — reason: {}",
                    key, previous, req.value(), caller.email(), req.justification());
        } else {
            log.info("Setting {} changed {} -> {} by {}", key, previous, req.value(), caller.email());
        }

        return new SettingResponse(setting.getSettingKey(), setting.getSettingValue(),
                setting.getValueType(), setting.getDescription());
    }

    private void validate(String valueType, String value) {
        switch (valueType) {
            case "boolean" -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "This setting must be true or false.");
                }
            }
            case "number" -> {
                try {
                    Long.parseLong(value);
                } catch (NumberFormatException e) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "This setting must be a whole number.");
                }
            }
            default -> { /* string / json — no constraint worth enforcing here */ }
        }
    }
}
