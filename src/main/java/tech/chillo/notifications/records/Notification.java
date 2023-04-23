package tech.chillo.notifications.records;

import com.fasterxml.jackson.annotation.JsonProperty;
import tech.chillo.notifications.enums.NotificationType;

import java.util.List;
import java.util.Map;

public record Notification(
        @JsonProperty("application") String application,
        @JsonProperty("template") String template,
        @JsonProperty("subject") String subject,
        @JsonProperty("eventId") String eventId,
        @JsonProperty("message") String message,
        @JsonProperty("channels") List<NotificationType> channels,
        @JsonProperty("from") Profile from,
        @JsonProperty("contacts") List<Profile> contacts,
        @JsonProperty("params") Map<String, Object> params
) {
}
