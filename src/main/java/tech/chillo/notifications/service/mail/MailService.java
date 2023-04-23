package tech.chillo.notifications.service.mail;

import com.google.common.base.Strings;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import tech.chillo.notifications.entity.Notification;
import tech.chillo.notifications.entity.NotificationStatus;
import tech.chillo.notifications.entity.NotificationTemplate;
import tech.chillo.notifications.entity.Recipient;
import tech.chillo.notifications.enums.NotificationType;
import tech.chillo.notifications.repository.NotificationTemplateRepository;

import javax.mail.MessagingException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@AllArgsConstructor
@Service
public class MailService {
    private final NotificationTemplateRepository notificationTemplateRepository;

    private final JavaMailSender mailSender;

    @Async
    public List<NotificationStatus> send(final Notification notification) {
        return notification.getContacts().parallelStream().map((Recipient to) -> {
            try {
                notification.setContacts(Set.of(to));
                Map<String, Object> params = notification.getParams();

                if (params == null) {
                    params = new HashMap<>();
                }
                final Object message = params.get("message");
                String messageAsString = null;
                if (message != null) {
                    messageAsString = message.toString();
                    final BeanInfo beanInfo = Introspector.getBeanInfo(Recipient.class);
                    for (final PropertyDescriptor propertyDesc : beanInfo.getPropertyDescriptors()) {
                        final String propertyName = propertyDesc.getName();
                        final Object value = propertyDesc.getReadMethod().invoke(to);
                        if (!Strings.isNullOrEmpty(String.valueOf(value)) && value instanceof String) {
                            if (Objects.equals(propertyName, "phone")) {
                                messageAsString = messageAsString.replace(String.format("%s%s%s", "{{", propertyName, "}}"), String.format("00%s%s", to.getPhoneIndex(), to.getPhone()));
                            } else {
                                messageAsString = messageAsString.replace(String.format("%s%s%s", "{{", propertyName, "}}"), (CharSequence) value);
                            }
                        }
                    }
                    messageAsString = messageAsString.replaceAll(Pattern.quote("\\n"), Matcher.quoteReplacement("<br />"));
                }
                params.put("message", messageAsString);
                params.put("firstName", to.getFirstName());
                params.put("lastName", to.getLastName());
                params.put("civility", to.getCivility());
                params.put("email", to.getEmail());
                params.put("phone", to.getPhone());
                params.put("phoneIndex", to.getPhoneIndex());
                final Context context = new Context();
                context.setVariables(params);
                String messageToSend = notification.getMessage();

                if (!Strings.isNullOrEmpty(notification.getTemplate())) {
                    NotificationTemplate notificationTemplate = this.notificationTemplateRepository
                            .findByApplicationAndName(notification.getApplication(), notification.getTemplate())
                            .orElseThrow(() -> new IllegalArgumentException(String.format("Aucun template %s n'existe pour %s", notification.getTemplate(), notification.getApplication())));
                    //final String template = this.textTemplateEngine.process(notificationTemplate.getContent(), context);
                    messageToSend = this.processTemplate(params, notificationTemplate.getContent());
                } else {
                    messageToSend = messageToSend.replaceAll(Pattern.quote("{{"), Matcher.quoteReplacement("${"))
                            .replaceAll(Pattern.quote("}}"), Matcher.quoteReplacement("}"));

                    Parser parser = Parser.builder().build();
                    Node document = parser.parse(messageToSend);
                    HtmlRenderer renderer = HtmlRenderer.builder().build();
                    messageToSend = renderer.render(document);
                    messageToSend = messageToSend.replaceAll("(\r\n|\n)", "<br />");
                    messageToSend = this.processTemplate(params, messageToSend);
                }

                this.sendMessage(notification, messageToSend);

                final NotificationStatus notificationStatus = new NotificationStatus();
                final Object eventId = notification.getEventId();
                notificationStatus.setEventId((String) eventId);
                notificationStatus.setUserId(to.getId());
                notificationStatus.setChannel(NotificationType.MAIL);
                return notificationStatus;
            } catch (final MessagingException | IntrospectionException | InvocationTargetException | IllegalAccessException e) {
                e.printStackTrace();
            }
            return null;
        }).collect(Collectors.toList());
    }

    private void sendMessage(final Notification notification, final String template) throws MessagingException {
        final MimeMessage mimeMessage = this.mailSender.createMimeMessage();
        final MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, StandardCharsets.UTF_8.name());
        final InternetAddress[] mappedRecipients = this.mappedUsers(notification.getContacts());
        helper.setTo(mappedRecipients);
        final InternetAddress[] mappedCC = this.mappedUsers(notification.getCc());
        helper.setCc(mappedCC);
        final InternetAddress[] mappedCCI = this.mappedUsers(notification.getCci());
        helper.setCc(mappedCCI);
        final InternetAddress from = this.getInternetAddress(notification.getFrom().getFirstName(), notification.getFrom().getLastName(), notification.getFrom().getEmail());
        helper.setFrom(Objects.requireNonNull(from));
        helper.setSubject(notification.getSubject());
        helper.setText(template, true);
        this.mailSender.send(mimeMessage);
    }

    private InternetAddress[] mappedUsers(final Set<Recipient> recipients) {

        return recipients.stream().map((Recipient to) -> this.getInternetAddress(to.getFirstName(), to.getLastName(), to.getEmail()))
                .toArray(InternetAddress[]::new);
    }

    private InternetAddress getInternetAddress(final String firstname, final String lastname, final String email) {
        try {
            final String name = String.format("%s %s", firstname, lastname);
            return new InternetAddress(email, name);
        } catch (final UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return null;
    }

    private String processTemplate(Map model, String template) {
        try {
            Template t = new Template("TemplateFromDBName", template, null);
            Writer out = new StringWriter();
            t.process(model, out);
            return out.toString();

        } catch (TemplateException | IOException e) {
            e.printStackTrace();
        }
        return null;
    }

}
