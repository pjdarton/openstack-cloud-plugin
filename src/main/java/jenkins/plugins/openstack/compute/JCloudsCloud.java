package jenkins.plugins.openstack.compute;

import java.io.IOException;
import java.io.StringWriter;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.ServletException;

import hudson.model.Item;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedPlannedNode;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import com.google.common.base.Objects;

import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.Secret;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.openstack4j.api.Builders;
import org.openstack4j.model.compute.BDMDestType;
import org.openstack4j.model.compute.BDMSourceType;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.BlockDeviceMappingBuilder;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;

/**
 * The JClouds version of the Jenkins Cloud.
 *
 * @author Vijay Kiran
 */
public class JCloudsCloud extends Cloud implements SlaveOptions.Holder {

    private static final Logger LOGGER = Logger.getLogger(JCloudsCloud.class.getName());

    public final @Nonnull String endPointUrl;
    public final @Nonnull String identity;
    public final @Nonnull Secret credential;
    // OpenStack4j requires null when there is no zone configured
    public final @CheckForNull String zone;

    private final @Nonnull List<JCloudsSlaveTemplate> templates;

    // Make sure only diff of defaults is saved so when plugin defaults will change users are not stuck with outdated config
    private /*final*/ @Nonnull SlaveOptions slaveOptions;

    // Backward compatibility
    private transient @Deprecated Integer instanceCap;
    private transient @Deprecated Integer retentionTime;
    private transient @Deprecated Integer startTimeout;
    private transient @Deprecated Boolean floatingIps;

    // TODO: refactor to interface/extension point
    public enum SlaveType {
        SSH {
            @Override
            public ComputerLauncher createLauncher(@Nonnull JCloudsSlave slave) throws IOException {
                int maxNumRetries = 5;
                int retryWaitTime = 15;

                SlaveOptions opts = slave.getSlaveOptions();
                String credentialsId = opts.getCredentialsId();
                if (credentialsId == null) {
                    throw new ProvisioningFailedException("No ssh credentials selected");
                }

                String publicAddress = slave.getPublicAddressIpv4();
                if (publicAddress == null) {
                    throw new IOException("The slave is likely deleted");
                }
                if ("0.0.0.0".equals(publicAddress)) {
                    throw new IOException("Invalid host 0.0.0.0, your host is most likely waiting for an ip address.");
                }

                Integer timeout = opts.getStartTimeout();
                timeout = timeout == null ? 0 : (timeout / 1000); // Never propagate null - always set some timeout

                return new SSHLauncher(publicAddress, 22, credentialsId, opts.getJvmOptions(), null, "", "", timeout, maxNumRetries, retryWaitTime);
            }

            @Override
            public boolean isReady(@Nonnull JCloudsSlave slave) {
            	
            	// richnou: 
            	//	Use Ipv4 Method to make sure IPV4 is the default here
            	//	OVH cloud provider returns IPV6 as last address, and getPublicAddress returns the last address
            	//  The Socket connection test then does not work.
            	//	This method could return the address object and work on it, but for now stick to IPV4
            	//  for the sake of simplicity
            	//
                String publicAddress = slave.getPublicAddressIpv4();
                // Wait until ssh is exposed not to timeout for too long in ssh-slaves launcher
                try {
                    Socket socket = new Socket();
                    socket.connect(new InetSocketAddress(publicAddress, 22), 500);
                    socket.close();
                    return true;
                } catch (ConnectException|NoRouteToHostException|SocketTimeoutException ex) {
                    // Exactly what we are looking for
                    LOGGER.log(Level.FINEST, "SSH port at "+publicAddress+" not open (yet)", ex);
                    return false;
                } catch (IOException ex) {
                    // TODO: General IOException to be understood and handled explicitly
                    LOGGER.log(Level.INFO, "SSH port  at "+publicAddress+" not (yet) open?", ex);
                    return false;
                } catch (Exception ex) {
                    LOGGER.log(Level.WARNING, "SSH probe failed", ex);
                    // We have no idea what happen. Log the cause and proceed with the server so it fail fast.
                    return true;
                }
            }
        },
        JNLP {
            @Override
            public ComputerLauncher createLauncher(@Nonnull JCloudsSlave slave) throws IOException {
                Jenkins.getActiveInstance().addNode(slave);
                return new JNLPLauncher();
            }

            @Override
            public boolean isReady(@Nonnull JCloudsSlave slave) {
                // The address might not be visible at all so let's just wait for connection.
                return slave.getChannel() != null;
            }
        };

        /**
         * Create launcher to be used to start the computer.
         */
        public abstract ComputerLauncher createLauncher(@Nonnull JCloudsSlave slave) throws IOException;

        /**
         * Detect the machine is provisioned and can be added to Jenkins for launching.
         *
         * This is guaranteed to be called after server is/was ACTIVE.
         */
        public abstract boolean isReady(@Nonnull JCloudsSlave slave);
    }

    public enum BootSource {
        IMAGE {
            public String toDisplayName() { return "Image"; }

            @Override
            public List<String> listAllNames(Openstack openstack) {
                final Map<String, ?> images = openstack.getImages();
                final List<String> allNames = new ArrayList<String>(images.size());
                allNames.addAll(images.keySet());
                return allNames;
            }

            @Override
            public List<String> findMatchingIds(Openstack openstack, String nameOrId) {
                return openstack.getImageIdsFor(nameOrId);
            }

            @Override
            public void setServerBootSource(ServerCreateBuilder builder,  String id) {
                builder.image(id);
            }

            @Override
            public void afterProvisioning(Server server, Openstack openstack, String id) {
            }
        },
        VOLUMESNAPSHOT {
            public String toDisplayName() { return "Volume Snapshot"; }

            @Override
            public List<String> listAllNames(Openstack openstack) {
                final Map<String, ?> images = openstack.getVolumeSnapshots();
                final List<String> allNames = new ArrayList<String>(images.size());
                allNames.addAll(images.keySet());
                return allNames;
            }

            @Override
            public List<String> findMatchingIds(Openstack openstack, String nameOrId) {
                return openstack.getVolumeSnapshotIdsFor(nameOrId);
            }

            @Override
            public void setServerBootSource(ServerCreateBuilder builder, String id) {
                BlockDeviceMappingBuilder volumeBuilder = Builders.blockDeviceMapping()
                        .sourceType(BDMSourceType.SNAPSHOT)
                        .destinationType(BDMDestType.VOLUME)
                        .uuid(id)
                        .deleteOnTermination(true)
                        .bootIndex(0);
                builder.blockDevice(volumeBuilder.build());
            }

            @Override
            public void afterProvisioning(Server server, Openstack openstack, String id) {
                /*
                 * OpenStack creates a Volume for the Instance to boot from but
                 * it does not give that Volume a name or description. We do
                 * this so that humans can recognize those Volumes.
                 */
                final List<String> volumeIds = server.getOsExtendedVolumesAttached();
                final String instanceId = server.getId();
                final String instanceName = server.getName();
                int i = 0;
                final String newVolumeDescription = "For " + instanceName + " (" + instanceId
                        + "), from VolumeSnapshot " + id + ".";
                for (final String volumeId : volumeIds) {
                    final String newVolumeName = instanceName + '[' + (i++) + ']';
                    openstack.setVolumeNameAndDescription(volumeId, newVolumeName, newVolumeDescription);
                }
            }
        };
        /** Human-readable name of this kind of {@link BootSource}. */
        public abstract String toDisplayName();

        /**
         * Lists all the names (of this kind of {@link BootSource}) that the
         * user could choose between.
         * 
         * @param openstack
         *            Means of communicating with the OpenStack service.
         */
        public abstract List<String> listAllNames(Openstack openstack);

        /**
         * Lists all the IDs (of this kind of {@link BootSource}) matching the
         * given nameOrId.
         * 
         * @param openstack
         *            Means of communicating with the OpenStack service.
         * @param nameOrId
         *            The user's selected name (or ID). This is most likely a
         *            value previously returned by
         *            {@link #listAllNames(Openstack)}.
         */
        public abstract List<String> findMatchingIds(Openstack openstack, String nameOrId);

        /**
         * Configures the given {@link ServerCreateBuilder} to specify that the
         * newly provisioned server should boot from the specified ID.
         * 
         * @param builder
         *            The server specification that is under construction. This
         *            will be amended.
         * @param id
         *            The ID (of this kind of {@link BootSource}) that the
         *            server should boot from. This is most likely a value
         *            previously returned by
         *            {@link #findMatchingIds(Openstack, String)}.
         */
        public abstract void setServerBootSource(ServerCreateBuilder builder, String id);

        /**
         * Called after a server has been provisioned.
         * 
         * @param server
         *            The newly-provisioned server.
         * @param openstack
         *            Means of communicating with the OpenStack service.
         * @param id
         *            The ID (of this kind of {@link BootSource}) that was
         *            previously passed to
         *            {@link #setServerBootSource(ServerCreateBuilder, String)}.
         */
        public abstract void afterProvisioning(Server server, Openstack openstack, String id);

        /**
         * Turns a string into an enum value.
         * <p>
         * Note: Unlike {@link #valueOf(String)} this returns null on failure.
         * 
         * @param s
         *            The string form. This may be null.
         * @return The enum whose {@link #name()}==s, or null if none match.
         */
        public static @CheckForNull BootSource fromString(String s) {
            final String sOrNull = Util.fixEmpty(s);
            if (sOrNull != null) {
                for (final BootSource i : values()) {
                    if (sOrNull.equals(i.name()))
                        return i;
                }
            }
            return null;
        }
    }

    public static @Nonnull List<JCloudsCloud> getClouds() {
        List<JCloudsCloud> clouds = new ArrayList<>();
        for (Cloud c : Jenkins.getActiveInstance().clouds) {
            if (JCloudsCloud.class.isInstance(c)) {
                clouds.add((JCloudsCloud) c);
            }
        }

        return clouds;
    }

    public static @Nonnull JCloudsCloud getByName(@Nonnull String name) throws IllegalArgumentException {
        Cloud cloud = Jenkins.getActiveInstance().clouds.getByName(name);
        if (cloud instanceof JCloudsCloud) return (JCloudsCloud) cloud;
        throw new IllegalArgumentException(name + " is not an OpenStack cloud: " + cloud);
    }

    @DataBoundConstructor @Restricted(DoNotUse.class)
    public JCloudsCloud(
            final String name, final String identity, final String credential, final String endPointUrl, final String zone,
            final SlaveOptions slaveOptions,
            final List<JCloudsSlaveTemplate> templates
    ) {
        super(Util.fixNull(name).trim());
        this.endPointUrl = Util.fixNull(endPointUrl).trim();
        this.identity = Util.fixNull(identity).trim();
        this.credential = Secret.fromString(credential);
        this.zone = Util.fixEmptyAndTrim(zone);

        this.slaveOptions = slaveOptions.eraseDefaults(DescriptorImpl.DEFAULTS);

        this.templates = Collections.unmodifiableList(Objects.firstNonNull(templates, Collections.<JCloudsSlaveTemplate> emptyList()));
        injectReferenceIntoTemplates();
    }

    @SuppressWarnings({"unused", "deprecation"})
    private Object readResolve() {
        if (retentionTime != null || startTimeout != null || floatingIps != null || instanceCap != null) {
            SlaveOptions carry = SlaveOptions.builder()
                    .instanceCap(instanceCap)
                    .retentionTime(retentionTime)
                    .startTimeout(startTimeout)
                    .floatingIpPool(floatingIps ? "public" : null)
                    .build()
            ;
            slaveOptions = DescriptorImpl.DEFAULTS.override(carry);
            retentionTime = null;
            startTimeout = null;
            floatingIps = null;
            instanceCap = null;
        }

        injectReferenceIntoTemplates();

        return this;
    }

    private void injectReferenceIntoTemplates() {
        for(JCloudsSlaveTemplate t: templates) {
            t.setOwner(this);
        }
    }

    public @Nonnull SlaveOptions getEffectiveSlaveOptions() {
        return DescriptorImpl.DEFAULTS.override(slaveOptions);
    }

    public @Nonnull SlaveOptions getRawSlaveOptions() {
        return slaveOptions;
    }

    public @Nonnull List<JCloudsSlaveTemplate> getTemplates() {
        return templates;
    }

    /**
     * Get a queue of templates to be used to provision slaves of label.
     *
     * The queue contains the same template in as many instances as is the number of machines that can be safely
     * provisioned without violating instanceCap constrain.
     */
    private @CheckForNull Queue<JCloudsSlaveTemplate> getAvailableTemplateProvider(@CheckForNull Label label) {
        final String labelString = (label != null) ? label.toString() : "none";
        LOGGER.log(Level.FINEST,
                "{0}.getAvailableTemplateProvider({1}) is calling getOpenstack().getRunningNodes() on {2}",
                new Object[]{ this, labelString, Thread.currentThread() });
        final List<Server> runningNodes = getOpenstack().getRunningNodes();
        final int globalMax = getEffectiveSlaveOptions().getInstanceCap();

        final Queue<JCloudsSlaveTemplate> queue = new ConcurrentLinkedDeque<>();
        int globalCapacity = globalMax - runningNodes.size();
        if (globalCapacity <= 0) {
            LOGGER.log(Level.INFO,
                    "Global instance cap ({0}) reached while adding capacity for label: {1}",
                    new Object[] { globalMax, labelString}
            );
            return queue; // No need to proceed any further;
        }

        final Map<JCloudsSlaveTemplate, Integer> template2capacity = new LinkedHashMap<>();
        for (JCloudsSlaveTemplate t : templates) {
            if (t.canProvision(label)) {
                final int templateMax = t.getEffectiveSlaveOptions().getInstanceCap();

                int templateCapacity = templateMax;
                for (Server server : runningNodes) {
                    if (t.hasProvisioned(server)) {
                        templateCapacity--;
                    }
                }

                if (templateCapacity > 0) {
                    template2capacity.put(t, templateCapacity);
                } else {
                    LOGGER.log(Level.INFO,
                            "Template instance cap for {0} ({1}) reached while adding capacity for label: {2}",
                            new Object[] { t.name, templateMax, labelString }
                    );
                }
            }
        }

        done: for (Map.Entry<JCloudsSlaveTemplate, Integer> e : template2capacity.entrySet()) {
            for (int i = e.getValue(); i > 0; i--) {
                if (globalCapacity > 0) {
                    queue.add(e.getKey());
                    globalCapacity--;
                } else {
                    LOGGER.log(Level.INFO,
                            "Global instance cap ({0}) reached while adding capacity for label: {1}",
                            new Object[] { globalMax, labelString}
                    );
                    break done;
                }
            }
        }

        return queue;
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
        Queue<JCloudsSlaveTemplate> templateProvider = getAvailableTemplateProvider(label);

        List<PlannedNode> plannedNodeList = new ArrayList<>();
        while (excessWorkload > 0 && !Jenkins.getActiveInstance().isQuietingDown() && !Jenkins.getActiveInstance().isTerminating()) {

            final JCloudsSlaveTemplate template = templateProvider.poll();
            if (template == null) {
                LOGGER.info("Instance cap exceeded on all available templates");
                break;
            }

            LOGGER.fine("Provisioning slave for " + label + " from template " + template.name);

            int numExecutors = template.getEffectiveSlaveOptions().getNumExecutors();

            ProvisioningActivity.Id id = new ProvisioningActivity.Id(this.name, template.name);
            Future<Node> task = Computer.threadPoolForRemoting.submit(new NodeCallable(this, template, id));
            plannedNodeList.add(new TrackedPlannedNode(id, numExecutors, task));

            excessWorkload -= numExecutors;
        }
        return plannedNodeList;
    }

    private static final class NodeCallable implements Callable<Node> {
        private final JCloudsCloud cloud;
        private final JCloudsSlaveTemplate template;
        private final ProvisioningActivity.Id id;

        public NodeCallable(JCloudsCloud cloud, JCloudsSlaveTemplate template, ProvisioningActivity.Id id) {
            this.cloud = cloud;
            this.template = template;
            this.id = id;
        }

        @Override
        public Node call() throws Exception {
            // TODO: record the output somewhere
            JCloudsSlave jcloudsSlave = template.provisionSlave(cloud, id, StreamTaskListener.fromStdout());

            LOGGER.fine(String.format("Slave %s launched successfully", jcloudsSlave.getDisplayName()));
            return jcloudsSlave;
        }
    }

    @Restricted(NoExternalUse.class)
    public /*for mocking*/ boolean isSlaveReadyToLaunch(@Nonnull JCloudsSlave slave) {
        return slave.getSlaveOptions().getSlaveType().isReady(slave);
    }

    @Override
    public boolean canProvision(final Label label) {
        for (JCloudsSlaveTemplate t : templates)
            if (t.canProvision(label))
                return true;
        return false;
    }

    public @CheckForNull JCloudsSlaveTemplate getTemplate(String name) {
        for (JCloudsSlaveTemplate t : templates)
            if (t.name.equals(name))
                return t;
        return null;
    }

    /**
     * Provisions a new node manually (by clicking a button in the computer list)
     *
     * @param req  {@link StaplerRequest}
     * @param rsp  {@link StaplerResponse}
     * @param name Name of the template to provision
     */
    @Restricted(NoExternalUse.class)
    public void doProvision(
            StaplerRequest req, StaplerResponse rsp, @QueryParameter String name
    ) throws ServletException, IOException, Descriptor.FormException, InterruptedException {

        // Temporary workaround for https://issues.jenkins-ci.org/browse/JENKINS-37616
        // Using Item.CONFIGURE as users authorized to do so can provision via job execution.
        // Once the plugins starts to depend on core new enough, we can use Cloud.PROVISION again.
        if (!hasPermission(Item.CONFIGURE) && !hasPermission(Cloud.PROVISION)) {
            checkPermission(Cloud.PROVISION);
        }

        if (name == null) {
            sendError("The slave template name query parameter is missing", req, rsp);
            return;
        }
        JCloudsSlaveTemplate t = getTemplate(name);
        if (t == null) {
            sendError("No such slave template with name : " + name, req, rsp);
            return;
        }

        List<Server> nodes = getOpenstack().getRunningNodes();
        final int global = nodes.size();

        int globalCap = getEffectiveSlaveOptions().getInstanceCap();
        if (global >= globalCap) {
            String msg = String.format("Instance cap of %s is now reached: %d", this.name, globalCap);
            sendError(msg, req, rsp);
            return;
        }

        int template = 0;
        for (Server node : nodes) {
            if (t.hasProvisioned(node)) {
                template++;
            }
        }

        int templateCap = t.getEffectiveSlaveOptions().getInstanceCap();
        if (template >= templateCap) {
            String msg = String.format("Instance cap for this template (%s/%s) is now reached: %d", this.name, name, templateCap);
            sendError(msg, req, rsp);
            return;
        }

        CloudStatistics.ProvisioningListener provisioningListener = CloudStatistics.ProvisioningListener.get();
        ProvisioningActivity.Id id = new ProvisioningActivity.Id(this.name, t.name);

        JCloudsSlave node;
        try {
            StringWriter sw = new StringWriter();
            StreamTaskListener listener = new StreamTaskListener(sw);
            provisioningListener.onStarted(id);
            node = t.provisionSlave(this, id, listener);
            provisioningListener.onComplete(id, node);
        } catch (Openstack.ActionFailed ex) {
            provisioningListener.onFailure(id, ex);
            req.setAttribute("message", ex.getMessage());
            req.setAttribute("exception", ex);
            rsp.forward(this,"error",req);
            return;
        } catch (Throwable ex) {
            provisioningListener.onFailure(id, ex);
            throw ex;
        }
        Jenkins.getActiveInstance().addNode(node);
        rsp.sendRedirect2(req.getContextPath() + "/computer/" + node.getNodeName());
    }

    /**
     * Get connected OpenStack client wrapper.
     */
    @Restricted(DoNotUse.class)
    public @Nonnull Openstack getOpenstack() {
        final Openstack os;
        try {
            os = Openstack.Factory.get(endPointUrl, identity, credential.getPlainText(), zone);
        } catch (FormValidation ex) {
            LOGGER.log(Level.SEVERE, "Openstack credentials invalid", ex);
            throw new RuntimeException("Openstack credentials invalid", ex);
        }
        return os;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {

        // Plugin default slave attributes - the root of all overriding
        private static final SlaveOptions DEFAULTS = SlaveOptions.builder()
                .instanceCap(10)
                .retentionTime(30)
                .startTimeout(600000)
                .numExecutors(1)
                .fsRoot("/jenkins")
                .securityGroups("default")
                .slaveType(SlaveType.SSH)
                .build()
        ;

        @Override
        public String getDisplayName() {
            return "Cloud (OpenStack)";
        }

        public static SlaveOptions getDefaultOptions() {
            return DEFAULTS;
        }

        @Restricted(DoNotUse.class)
        public FormValidation doTestConnection(
                @QueryParameter String zone,
                @QueryParameter String endPointUrl,
                @QueryParameter String identity,
                @QueryParameter String credential
        ) {
            try {
                Throwable ex = Openstack.Factory.get(endPointUrl, identity, credential, zone).sanityCheck();
                if (ex != null) {
                    return FormValidation.warning(ex, "Connection not validated, plugin might not operate correctly: " + ex.getMessage());
                }
            } catch (FormValidation ex) {
                return ex;
            } catch (Exception ex) {
                return FormValidation.error(ex, "Cannot connect to specified cloud, please check the identity and credentials: " + ex.getMessage());
            }
            return FormValidation.ok("Connection succeeded!");
        }

        @Restricted(DoNotUse.class)
        public FormValidation doCheckEndPointUrl(@QueryParameter String value) {
            if (Util.fixEmpty(value) == null) return FormValidation.validateRequired(value);

            try {
                new URL(value);
            } catch (MalformedURLException ex) {
                FormValidation.error(ex, "The endpoint must be an URL"  );
            }
            return FormValidation.ok();
        }
    }

    /**
     * The request to provision was not fulfilled.
     */
    public static final class ProvisioningFailedException extends RuntimeException {

        public ProvisioningFailedException(String msg, Throwable cause) {
            super(msg, cause);
        }

        public ProvisioningFailedException(String msg) {
            super(msg);
        }
    }
}
