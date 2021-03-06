package hudson.plugins.emailext;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.matrix.MatrixAggregatable;
import hudson.matrix.MatrixAggregator;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixRun;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.plugins.emailext.plugins.ContentBuilder;
import hudson.plugins.emailext.plugins.CssInliner;
import hudson.plugins.emailext.plugins.EmailTrigger;
import hudson.plugins.emailext.plugins.RecipientProvider;
import hudson.plugins.emailext.plugins.content.TriggerNameContent;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.MailMessageIdAction;
import hudson.tasks.Mailer;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ConnectException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.mail.Address;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.SendFailedException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.apache.commons.lang.StringUtils;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.kohsuke.groovy.sandbox.SandboxTransformer;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * {@link Publisher} that sends notification e-mail.
 */
public class ExtendedEmailPublisher extends Notifier implements MatrixAggregatable {

    private static final Logger LOGGER = Logger.getLogger(ExtendedEmailPublisher.class.getName());

    private static final String CONTENT_TRANSFER_ENCODING = System.getProperty(ExtendedEmailPublisher.class.getName() + ".Content-Transfer-Encoding");

    public static final String DEFAULT_RECIPIENTS_TEXT = "";

    public static final String DEFAULT_SUBJECT_TEXT = "$PROJECT_NAME - Build # $BUILD_NUMBER - $BUILD_STATUS!";

    public static final String DEFAULT_BODY_TEXT = "$PROJECT_NAME - Build # $BUILD_NUMBER - $BUILD_STATUS:\n\n"
            + "Check console output at $BUILD_URL to view the results.";

    public static final String DEFAULT_EMERGENCY_REROUTE_TEXT = "";

    public static final String PROJECT_DEFAULT_SUBJECT_TEXT = "$PROJECT_DEFAULT_SUBJECT";

    public static final String PROJECT_DEFAULT_BODY_TEXT = "$PROJECT_DEFAULT_CONTENT";

    /**
     * A comma-separated list of email recipient that will be used for every
     * theTrigger.
     */
    public String recipientList = "";

    /**
     * This is the list of email theTriggers that the project has configured
     */
    public List<EmailTrigger> configuredTriggers = new ArrayList<EmailTrigger>();

    /**
     * The contentType of the emails for this project (text/html, text/plain,
     * etc).
     */
    public String contentType;

    /**
     * The default subject of the emails for this project.
     * ($PROJECT_DEFAULT_SUBJECT)
     */
    public String defaultSubject;

    /**
     * The default body of the emails for this project. ($PROJECT_DEFAULT_BODY)
     */
    public String defaultContent;

    /**
     * The project wide set of attachments.
     */
    public String attachmentsPattern;

    /**
     * The project's pre-send script.
     */
    public String presendScript;

    /**
     * True to attach the log from the build to the email.
     */
    public boolean attachBuildLog;

    /**
     * True to compress the log from the build before attaching to the email
     */
    public boolean compressBuildLog;

    /**
     * Reply-To value for the e-mail
     */
    public String replyTo;

    /**
     * If true, save the generated email content to email-ext-message.[txt|html]
     */
    public boolean saveOutput = false;

    /**
     * How to theTrigger the email if the project is a matrix project.
     */
    public MatrixTriggerMode matrixTriggerMode;

    @DataBoundConstructor
    public ExtendedEmailPublisher(String project_recipient_list, String project_content_type, String project_default_subject,
            String project_default_content, String project_attachments, String project_presend_script,
            int project_attach_buildlog, String project_replyto, boolean project_save_output,
            List<EmailTrigger> project_triggers, MatrixTriggerMode matrixTriggerMode) {
        this.recipientList = project_recipient_list;
        this.contentType = project_content_type;
        this.defaultSubject = project_default_subject;
        this.defaultContent = project_default_content;
        this.attachmentsPattern = project_attachments;
        this.presendScript = project_presend_script;
        this.attachBuildLog = project_attach_buildlog > 0;
        this.compressBuildLog = project_attach_buildlog > 1;
        this.replyTo = project_replyto;
        this.saveOutput = project_save_output;
        this.configuredTriggers = project_triggers;
        this.matrixTriggerMode = matrixTriggerMode;
    }

    public ExtendedEmailPublisher() {

    }
    
    /**
     * Get the list of configured email theTriggers for this project.
     *
     * @return
     */
    public List<EmailTrigger> getConfiguredTriggers() {
        if (configuredTriggers == null) {
            configuredTriggers = new ArrayList<EmailTrigger>();
        }
        return configuredTriggers;
    }
  
    public MatrixTriggerMode getMatrixTriggerMode() {
        return matrixTriggerMode == null ? MatrixTriggerMode.BOTH : matrixTriggerMode;
    }

    public void setMatrixTriggerMode(MatrixTriggerMode matrixTriggerMode) {
        this.matrixTriggerMode = matrixTriggerMode;
    }

    public void debug(PrintStream p, String format, Object... args) {
        getDescriptor().debug(p, format, args);
    }

    @Override
    public Collection<? extends Action> getProjectActions(AbstractProject<?, ?> project) {
        // only allow the user to see the email template testing action if they can
        // configure the project itself.        
        if (project.hasPermission(Item.CONFIGURE)) {
            return Collections.singletonList(new EmailExtTemplateAction(project));
        }
        return Collections.EMPTY_LIST;
    }

    @Override
    public boolean prebuild(AbstractBuild<?, ?> build, BuildListener listener) {
        debug(listener.getLogger(), "Checking for pre-build");
        if (!(build instanceof MatrixRun) || isExecuteOnMatrixNodes()) {
            debug(listener.getLogger(), "Executing pre-build step");
            return _perform(build, null, listener, true);
        }
        return true;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        debug(listener.getLogger(), "Checking for post-build");
        if (!(build instanceof MatrixRun) || isExecuteOnMatrixNodes()) {
            debug(listener.getLogger(), "Performing post-build step");
            return _perform(build, launcher, listener, false);
        }
        return true;
    }

    private boolean _perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener, boolean forPreBuild) {
        boolean emailTriggered = false;
        debug(listener.getLogger(), "Checking if email needs to be generated");
        final Multimap<String, EmailTrigger> triggered = ArrayListMultimap.create();

        for (EmailTrigger trigger : getConfiguredTriggers()) {
            if (trigger.isPreBuild() == forPreBuild && trigger.trigger(build, listener)) {
                String tName = trigger.getDescriptor().getDisplayName();
                triggered.put(tName, trigger);
                listener.getLogger().println("Email was triggered for: " + tName);
                emailTriggered = true;
            }
        }

        //Go through and remove triggers that are replaced by others
        List<String> replacedTriggers = new ArrayList<String>();

        for (Object tName : triggered.keySet()) {
            String triggerName = (String) tName;
            for (EmailTrigger trigger : (Collection<EmailTrigger>) triggered.get(triggerName)) {
                replacedTriggers.addAll(trigger.getDescriptor().getTriggerReplaceList());
            }
        }

        for (String triggerName : replacedTriggers) {
            triggered.removeAll(triggerName);
            listener.getLogger().println("Trigger " + triggerName + " was overridden by another trigger and will not send an email.");
        }

        if (emailTriggered && triggered.isEmpty()) {
            listener.getLogger().println("There is a circular trigger replacement with the email triggers.  No email is sent.");
            return false;
        } else if (triggered.isEmpty()) {
            listener.getLogger().println("No emails were triggered.");
            return true;
        }

        for (String triggerName : triggered.keySet()) {
            for (EmailTrigger trigger : triggered.get(triggerName)) {
                listener.getLogger().println("Sending email for trigger: " + triggerName);
                final ExtendedEmailPublisherContext context = new ExtendedEmailPublisherContext(this, build, launcher, listener);
                context.setTriggered(triggered);
                context.setTrigger(trigger);
                sendMail(context);
            }
        }

        return true;
    }

    private boolean sendMail(ExtendedEmailPublisherContext context) {
        try {
            MimeMessage msg = createMail(context);
            debug(context.getListener().getLogger(), "Successfully created MimeMessage");
            Address[] allRecipients = msg.getAllRecipients();
            int retries = 0;
            if (allRecipients != null) {
                if (executePresendScript(context, msg)) {
                    // presend script might have modified recipients:
                    allRecipients = msg.getAllRecipients();

                    if(StringUtils.isNotBlank(getDescriptor().getEmergencyReroute())) {
                        // clear out all the existing recipients
                        msg.setRecipients(Message.RecipientType.TO, (Address[])null);
                        msg.setRecipients(Message.RecipientType.CC, (Address[])null);
                        msg.setRecipients(Message.RecipientType.BCC, (Address[])null);
                        // and set the emergency reroute
                        msg.setRecipients(Message.RecipientType.TO, getDescriptor().getEmergencyReroute());
                    }

                    StringBuilder buf = new StringBuilder("Sending email to:");
                    for (Address a : allRecipients) {
                        buf.append(' ').append(a);
                    }
                    context.getListener().getLogger().println(buf);

                    while (true) {
                        try {
                            Transport.send(msg);
                            break;
                        } catch (SendFailedException e) {
                            if (e.getNextException() != null
                                    && ((e.getNextException() instanceof SocketException)
                                    || (e.getNextException() instanceof ConnectException))) {
                                context.getListener().getLogger().println("Socket error sending email, retrying once more in 10 seconds...");
                                Thread.sleep(10000);
                            } else {
                                Address[] addresses = e.getValidSentAddresses();
                                if (addresses != null && addresses.length > 0) {
                                    buf = new StringBuilder("Successfully sent to the following addresses:");
                                    for (Address a : addresses) {
                                        buf.append(' ').append(a);
                                    }
                                    context.getListener().getLogger().println(buf);
                                }
                                addresses = e.getValidUnsentAddresses();
                                if (addresses != null && addresses.length > 0) {
                                    buf = new StringBuilder("Error sending to the following VALID addresses:");
                                    for (Address a : addresses) {
                                        buf.append(' ').append(a);
                                    }
                                    context.getListener().getLogger().println(buf);
                                }
                                addresses = e.getInvalidAddresses();
                                if (addresses != null && addresses.length > 0) {
                                    buf = new StringBuilder("Error sending to the following INVALID addresses:");
                                    for (Address a : addresses) {
                                        buf.append(' ').append(a);
                                    }
                                    context.getListener().getLogger().println(buf);
                                }

                                debug(context.getListener().getLogger(), "SendFailedException message: " + e.getMessage());
                                break;
                            }
                        }
                        retries++;
                        if (retries > 1) {
                            context.getListener().getLogger().println("Failed after second try sending email");
                            break;
                        }
                    }
                    if (context.getBuild().getAction(MailMessageIdAction.class) == null) {
                        context.getBuild().addAction(new MailMessageIdAction(msg.getMessageID()));
                    }
                } else {
                    context.getListener().getLogger().println("Email sending was cancelled"
                            + " by user script.");
                }
                return true;
            } else {
                context.getListener().getLogger().println("An attempt to send an e-mail"
                        + " to empty list of recipients, ignored.");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not send email.", e);
            e.printStackTrace(context.getListener().error("Could not send email as a part of the post-build publishers."));
        }

        debug(context.getListener().getLogger(), "Some error occured trying to send the email...check the Jenkins log");
        return false;
    }

    public List<TokenMacro> getRuntimeMacros(ExtendedEmailPublisherContext context) {
        List<TokenMacro> macros = new ArrayList<TokenMacro>();
        macros.add(new TriggerNameContent(context.getTrigger().getDescriptor().getDisplayName()));
        return macros;
    }

    private boolean executePresendScript(ExtendedEmailPublisherContext context, MimeMessage msg)
            throws RuntimeException {
        boolean cancel = false;
        String script = ContentBuilder.transformText(presendScript, context, getRuntimeMacros(context));
        if (StringUtils.isNotBlank(script)) {
            debug(context.getListener().getLogger(), "Executing pre-send script");
            ClassLoader cl = Jenkins.getInstance().getPluginManager().uberClassLoader;
            ScriptSandbox sandbox = null;
            CompilerConfiguration cc = new CompilerConfiguration();
            cc.addCompilationCustomizers(new ImportCustomizer().addStarImports(
                    "jenkins",
                    "jenkins.model",
                    "hudson",
                    "hudson.model"));

            if (getDescriptor().isSecurityEnabled()) {
                debug(context.getListener().getLogger(), "Setting up sandbox for pre-send script");
                cc.addCompilationCustomizers(new SandboxTransformer());
                sandbox = new ScriptSandbox();
            }

            Binding binding = new Binding();
            binding.setVariable("build", context.getBuild());
            binding.setVariable("msg", msg);
            binding.setVariable("logger", context.getListener().getLogger());
            binding.setVariable("cancel", cancel);
            binding.setVariable("trigger", context.getTrigger());
            binding.setVariable("triggered", ImmutableMultimap.copyOf(context.getTriggered()));

            GroovyShell shell = new GroovyShell(cl, binding, cc);
            StringWriter out = new StringWriter();
            PrintWriter pw = new PrintWriter(out);

            if (sandbox != null) {
                sandbox.register();
            }

            try {
                Object output = shell.evaluate(script);
                if (output != null) {
                    pw.println("Result: " + output);
                    cancel = ((Boolean) shell.getVariable("cancel")).booleanValue();
                    debug(context.getListener().getLogger(), "Pre-send script set cancel to %b", cancel);
                }
            } catch (SecurityException e) {
                context.getListener().getLogger().println("Pre-send script tried to access secured objects: " + e.getMessage());
            } catch (Throwable t) {
                t.printStackTrace(pw);
                context.getListener().getLogger().println(out.toString());
                // should we cancel the sending of the email???
            }
            debug(context.getListener().getLogger(), out.toString());
        }
        return !cancel;
    }

    private MimeMessage createMail(ExtendedEmailPublisherContext context) throws MessagingException, IOException, InterruptedException {
        ExtendedEmailPublisherDescriptor descriptor = getDescriptor();
        boolean overrideGlobalSettings = descriptor.getOverrideGlobalSettings();

        MimeMessage msg;

        // If not overriding global settings, use the Mailer class to create a session and set the from address
        // Else we'll do it ourselves
        Session session;
        if (!overrideGlobalSettings) {
            debug(context.getListener().getLogger(), "NOT overriding default server settings, using Mailer to create session");
            session = Mailer.descriptor().createSession();
            msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(JenkinsLocationConfiguration.get().getAdminAddress()));
        } else {
            debug(context.getListener().getLogger(), "Overriding default server settings, creating our own session");
            session = descriptor.createSession();
            msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(descriptor.getAdminAddress()));
        }

        if (descriptor.isDebugMode()) {
            session.setDebugOut(context.getListener().getLogger());
        }

        String charset = Mailer.descriptor().getCharset();
        if (overrideGlobalSettings) {
            String overrideCharset = descriptor.getCharset();
            if (StringUtils.isNotBlank(overrideCharset)) {
                debug(context.getListener().getLogger(), "Overriding charset %s", overrideCharset);
                charset = overrideCharset;
            }
        }

        // Set the contents of the email
        msg.addHeader("X-Jenkins-Job", context.getBuild().getProject().getDisplayName());
        if (context.getBuild().getResult() != null) {
            msg.addHeader("X-Jenkins-Result", context.getBuild().getResult().toString());
        }
        msg.setSentDate(new Date());
        setSubject(context, msg, charset);

        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(getContent(context, charset));

        AttachmentUtils attachments = new AttachmentUtils(attachmentsPattern);
        attachments.attach(multipart, context);

        // add attachments from the email type if they are setup
        if (StringUtils.isNotBlank(context.getTrigger().getEmail().getAttachmentsPattern())) {
            AttachmentUtils typeAttachments = new AttachmentUtils(context.getTrigger().getEmail().getAttachmentsPattern());
            typeAttachments.attach(multipart, context);
        }

        if (attachBuildLog || context.getTrigger().getEmail().getAttachBuildLog()) {
            debug(context.getListener().getLogger(), "Request made to attach build log");
            AttachmentUtils.attachBuildLog(context, multipart, compressBuildLog || context.getTrigger().getEmail().getCompressBuildLog());
        }

        msg.setContent(multipart);

        EnvVars env = null;
        try {
            env = context.getBuild().getEnvironment(context.getListener());
        } catch (Exception e) {
            context.getListener().getLogger().println("Error retrieving environment vars: " + e.getMessage());
            // create an empty set of env vars
            env = new EnvVars();
        }
        
        // Get the recipients from the global list of addresses
        Set<InternetAddress> to = new LinkedHashSet<InternetAddress>();
        Set<InternetAddress> cc = new LinkedHashSet<InternetAddress>();
        Set<InternetAddress> bcc = new LinkedHashSet<InternetAddress>();

        String emergencyReroute = descriptor.getEmergencyReroute();

        if (StringUtils.isNotBlank(emergencyReroute)) {
            debug(context.getListener().getLogger(), "Emergency reroute turned on");
            EmailRecipientUtils.addAddressesFromRecipientList(to, cc, bcc, emergencyReroute, env, context.getListener());
            debug(context.getListener().getLogger(), "Emergency reroute is set to: " + emergencyReroute);
        } else {
            for (RecipientProvider provider : context.getTrigger().getEmail().getRecipientProviders()) {
                provider.addRecipients(context, env, to, cc, bcc);
            }
            
            descriptor.debug(context.getListener().getLogger(), "Adding recipients from trigger recipient list");
            EmailRecipientUtils.addAddressesFromRecipientList(to, cc, bcc, EmailRecipientUtils.getRecipientList(context, context.getTrigger().getEmail().getRecipientList()), env, context.getListener());
        }

        // remove the excluded recipients
        Set<InternetAddress> excludedRecipients = new LinkedHashSet<InternetAddress>();
        for (InternetAddress recipient : to) {
            if (EmailRecipientUtils.isExcludedRecipient(recipient.getAddress(), context.getListener())) {
                excludedRecipients.add(recipient);
            }
        }
        to.removeAll(excludedRecipients);
        cc.removeAll(excludedRecipients);
        bcc.removeAll(excludedRecipients);

        msg.setRecipients(Message.RecipientType.TO, to.toArray(new InternetAddress[to.size()]));
        if (cc.size() > 0) {
            msg.setRecipients(Message.RecipientType.CC, cc.toArray(new InternetAddress[cc.size()]));
        }
        if (bcc.size() > 0) {
            msg.setRecipients(Message.RecipientType.BCC, bcc.toArray(new InternetAddress[bcc.size()]));
        }

        Set<InternetAddress> replyToAddresses = new LinkedHashSet<InternetAddress>();

        if (StringUtils.isNotBlank(replyTo)) {
            EmailRecipientUtils.addAddressesFromRecipientList(replyToAddresses, null, null, EmailRecipientUtils.getRecipientList(context, replyTo), env, context.getListener());
        }

        if (StringUtils.isNotBlank(context.getTrigger().getEmail().getReplyTo())) {
            EmailRecipientUtils.addAddressesFromRecipientList(replyToAddresses, null, null, EmailRecipientUtils.getRecipientList(context, context.getTrigger().getEmail().getReplyTo()), env, context.getListener());
        }

        if (replyToAddresses.size() > 0) {
            msg.setReplyTo(replyToAddresses.toArray(new InternetAddress[replyToAddresses.size()]));
        }

        AbstractBuild<?, ?> pb = getPreviousBuild(context.getBuild(), context.getListener());
        if (pb != null) {
            // Send mails as replies until next successful build
            MailMessageIdAction b = pb.getAction(MailMessageIdAction.class);
            if (b != null && pb.getResult() != Result.SUCCESS) {
                debug(context.getListener().getLogger(), "Setting In-Reply-To since last build was not successful");
                msg.setHeader("In-Reply-To", b.messageId);
                msg.setHeader("References", b.messageId);
            }
        }

        if (CONTENT_TRANSFER_ENCODING != null) {
            msg.setHeader("Content-Transfer-Encoding", CONTENT_TRANSFER_ENCODING);
        }

        String listId = descriptor.getListId();
        if (listId != null) {
            msg.setHeader("List-ID", listId);
        }

        if (descriptor.getPrecedenceBulk()) {
            msg.setHeader("Precedence", "bulk");
        }

        return msg;
    }

    private void setSubject(ExtendedEmailPublisherContext context, MimeMessage msg, String charset)
            throws MessagingException {
        String subject = ContentBuilder.transformText(context.getTrigger().getEmail().getSubject(), context, getRuntimeMacros(context));
        msg.setSubject(subject, charset);
    }

    public boolean isExecuteOnMatrixNodes() {
        MatrixTriggerMode mtm = getMatrixTriggerMode();
        return MatrixTriggerMode.BOTH == mtm
                || MatrixTriggerMode.ONLY_CONFIGURATIONS == mtm;
    }

    private MimeBodyPart getContent(ExtendedEmailPublisherContext context, String charset)
            throws MessagingException {
        final String text = ContentBuilder.transformText(context.getTrigger().getEmail().getBody(), context, getRuntimeMacros(context));

        String messageContentType = context.getTrigger().getEmail().getContentType().equals("project") ? contentType : context.getTrigger().getEmail().getContentType();
        // contentType is null if the project was not reconfigured after upgrading.
        if (messageContentType == null || "default".equals(messageContentType)) {
            messageContentType = getDescriptor().getDefaultContentType();
            // The defaultContentType is null if the main Jenkins configuration
            // was not reconfigured after upgrading.
            if (messageContentType == null) {
                messageContentType = "text/plain";
            }
        }
        messageContentType += "; charset=" + charset;

        try {
            if (saveOutput) {
                Random random = new Random();
                String extension = ".html";
                if (messageContentType.startsWith("text/plain")) {
                    extension = ".txt";
                }

                FilePath savedOutput = new FilePath(context.getBuild().getWorkspace(),
                        String.format("%s-%s%d%s", context.getTrigger().getDescriptor().getDisplayName(), context.getBuild().getId(), random.nextInt(), extension));
                savedOutput.write(text, charset);
            }
        } catch (IOException e) {
            context.getListener().getLogger().println("Error trying to save email output to file. " + e.getMessage());
        } catch (InterruptedException e) {
            context.getListener().getLogger().println("Error trying to save email output to file. " + e.getMessage());
        }

        // set the email message text 
        // (plain text or HTML depending on the content type)
        MimeBodyPart msgPart = new MimeBodyPart();
        debug(context.getListener().getLogger(), "messageContentType = %s", messageContentType);
        if (messageContentType.startsWith("text/html")) {
            String inlinedCssHtml = new CssInliner().process(text);
            msgPart.setContent(inlinedCssHtml, messageContentType);
        } else {
            msgPart.setContent(text, messageContentType);
        }
        return msgPart;
    }

    @Override
    public boolean needsToRunAfterFinalized() {
        return true;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    /**
     * Looks for a previous build, so long as that is in fact completed.
     * Necessary since {@link #getRequiredMonitorService} does not wait for the
     * previous build, so in the case of parallel-capable jobs, we need to
     * behave sensibly when a later build actually finishes before an earlier
     * one.
     *
     * @param build a build for which we may be sending mail
     * @param listener a listener to which we may print warnings in case the
     * actual previous build is still in progress
     * @return the previous build, or null if that build is missing, or is still
     * in progress
     */
    public static @CheckForNull
    AbstractBuild<?, ?> getPreviousBuild(@Nonnull AbstractBuild<?, ?> build, TaskListener listener) {
        AbstractBuild<?, ?> previousBuild = build.getPreviousBuild();
        if (previousBuild != null && previousBuild.isBuilding()) {
            listener.getLogger().println(Messages.ExtendedEmailPublisher__is_still_in_progress_ignoring_for_purpo(previousBuild.getDisplayName()));
            return null;
        } else {
            return previousBuild;
        }
    }

    @Override
    public ExtendedEmailPublisherDescriptor getDescriptor() {
        return (ExtendedEmailPublisherDescriptor) Jenkins.getInstance().getDescriptor(getClass());
    }

    public MatrixAggregator createAggregator(MatrixBuild matrixbuild,
            Launcher launcher, BuildListener buildlistener) {
        return new MatrixAggregator(matrixbuild, launcher, buildlistener) {
            @Override
            public boolean endBuild() throws InterruptedException, IOException {
                LOGGER.log(Level.FINER, "end build of " + this.build.getDisplayName());

                // Will be run by parent so we check if needed to be executed by parent
                if (getMatrixTriggerMode().forParent) {
                    return ExtendedEmailPublisher.this._perform(this.build, this.launcher, this.listener, false);
                }
                return true;
            }

            @Override
            public boolean startBuild() throws InterruptedException, IOException {
                LOGGER.log(Level.FINER, "end build of " + this.build.getDisplayName());
                // Will be run by parent so we check if needed to be executed by parent 
                if (getMatrixTriggerMode().forParent) {
                    return ExtendedEmailPublisher.this._perform(this.build, this.launcher, this.listener, true);
                }
                return true;
            }
        };
    }
}
