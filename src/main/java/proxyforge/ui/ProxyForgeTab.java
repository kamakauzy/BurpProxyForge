package proxyforge.ui;

import proxyforge.models.ProxyForgeModels;
import proxyforge.models.ProxyForgeModels.ExtensionState;
import proxyforge.models.ProxyForgeModels.ProviderResult;
import proxyforge.models.ProxyForgeModels.ProviderType;
import proxyforge.models.ProxyForgeModels.ProxyEntry;
import proxyforge.models.ProxyForgeModels.RotationStrategy;
import proxyforge.models.ProxyForgeModels.ScopeRule;
import proxyforge.utils.ProxyForgeLogger;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class ProxyForgeTab extends JPanel
{
    private final ProxyForgeActions actions;
    private final ProxyForgeLogger logger;
    private final ProxyTableModel proxyTableModel = new ProxyTableModel();
    private final ScopeRuleTableModel scopeRuleTableModel = new ScopeRuleTableModel();
    private final JTable proxyTable = new JTable(proxyTableModel);
    private final JTable scopeRuleTable = new JTable(scopeRuleTableModel);
    private final JScrollPane proxyTableScrollPane = new JScrollPane(proxyTable);
    private final JScrollPane scopeRuleScrollPane = new JScrollPane(scopeRuleTable);
    private final JTextArea logArea = new JTextArea();
    private final JLabel statsLabel = new JLabel("No proxies loaded.");
    private final JComboBox<RotationStrategy> strategyCombo = new JComboBox<>();
    private final JTextField portField = new JTextField(6);
    private final JCheckBox autoStartCheck = new JCheckBox("Auto-start local proxy");
    private final JCheckBox manageBurpUpstreamCheck = new JCheckBox("Enable Burp upstream rule");
    private final JCheckBox persistSensitiveCheck = new JCheckBox("Persist provider secrets");
    private final JCheckBox externalBindCheck = new JCheckBox("Bind to all interfaces");
    private final JButton restartProxyButton = new JButton("Start / Restart Proxy");
    private final JButton stopProxyButton = new JButton("Stop Proxy");
    private final JButton rotateNowButton = new JButton("Rotate Now");
    private final JButton validateAllButton = new JButton("Validate All");
    private final JButton helpButton = new JButton("Help");
    private final ProviderPanel awsPanel;
    private final ProviderPanel cloudflarePanel;
    private final ProviderPanel vpsPanel;
    private boolean suppressSettingEvents;

    public ProxyForgeTab(ProxyForgeActions actions, ProxyForgeLogger logger)
    {
        super(new BorderLayout(12, 12));
        this.actions = Objects.requireNonNull(actions, "actions");
        this.logger = Objects.requireNonNull(logger, "logger");
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        awsPanel = new ProviderPanel(
            ProviderType.AWS_FIREPROX,
            List.of(
                new FieldSpec("accessKey", "Access Key", false),
                new FieldSpec("secretKey", "Secret Key", true),
                new FieldSpec("sessionToken", "Session Token", true),
                new FieldSpec("region", "Region", false),
                new FieldSpec("targetUrl", "Target URL", false)));

        cloudflarePanel = new ProviderPanel(
            ProviderType.CLOUDFLARE_FLAREPROX,
            List.of(
                new FieldSpec("apiToken", "API Token", true),
                new FieldSpec("accountId", "Account ID", false),
                new FieldSpec("workersSubdomain", "Workers Subdomain", false),
                new FieldSpec("targetUrl", "Target URL", false)));

        vpsPanel = new ProviderPanel(
            ProviderType.VPS_FORGE,
            List.of(
                new FieldSpec("vendor", "Vendor", false),
                new FieldSpec("apiToken", "API Token", true),
                new FieldSpec("region", "Region", false),
                new FieldSpec("instanceType", "Instance Type", false),
                new FieldSpec("accessKey", "AWS Access Key", false),
                new FieldSpec("secretKey", "AWS Secret Key", true),
                new FieldSpec("amiId", "AWS AMI ID", false),
                new FieldSpec("subnetId", "Subnet ID", false),
                new FieldSpec("securityGroupId", "Security Group ID", false),
                new FieldSpec("iamInstanceProfile", "IAM Instance Profile", false)));

        awsPanel.attach(this.actions, this::selectedProxy);
        cloudflarePanel.attach(this.actions, this::selectedProxy);
        vpsPanel.attach(this.actions, this::selectedProxy);

        add(buildMainSplitPane(), BorderLayout.CENTER);
        add(buildFooterPanel(), BorderLayout.SOUTH);

        logArea.setEditable(false);
        logArea.setRows(12);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);

        proxyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        scopeRuleTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        proxyTableScrollPane.setPreferredSize(new Dimension(520, 220));
        scopeRuleScrollPane.setPreferredSize(new Dimension(520, 170));

        strategyCombo.setModel(new DefaultComboBoxModel<>(RotationStrategy.values()));
        restartProxyButton.addActionListener(event -> applySettings(true));
        manageBurpUpstreamCheck.addActionListener(event ->
        {
            if (!suppressSettingEvents)
            {
                applySettings(false);
            }
        });
        stopProxyButton.addActionListener(event ->
        {
            actions.stopProxy();
            refresh();
        });
        rotateNowButton.addActionListener(event ->
        {
            actions.rotateNow();
            refresh();
        });
        validateAllButton.addActionListener(event ->
        {
            actions.validateAll();
            refresh();
        });
        helpButton.addActionListener(event -> showHelpDialog());

        logger.addListener(line -> SwingUtilities.invokeLater(() ->
        {
            logArea.append(line + System.lineSeparator());
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }));

        refresh();
    }

    public void refresh()
    {
        ExtensionState state = actions.currentState();
        String selectedProxyId = selectedProxy() == null ? null : selectedProxy().id;
        proxyTableModel.setRows(state.proxies);
        scopeRuleTableModel.setRows(state.scopeRules);
        if (selectedProxyId != null)
        {
            int row = proxyTableModel.indexOfProxyId(selectedProxyId);
            if (row >= 0)
            {
                proxyTable.getSelectionModel().setSelectionInterval(row, row);
            }
        }

        suppressSettingEvents = true;
        try
        {
            strategyCombo.setSelectedItem(state.settings.rotationStrategy);
            portField.setText(String.valueOf(state.settings.localProxyPort));
            autoStartCheck.setSelected(state.settings.autoStartProxy);
            manageBurpUpstreamCheck.setSelected(state.settings.manageBurpUpstreamProxy);
            persistSensitiveCheck.setSelected(state.settings.persistSensitiveFields);
            externalBindCheck.setSelected(state.settings.allowExternalBind);
        }
        finally
        {
            suppressSettingEvents = false;
        }

        awsPanel.load(state);
        cloudflarePanel.load(state);
        vpsPanel.load(state);

        long activeCount = state.proxies.stream().filter(proxy -> proxy.enabled).count();
        long forwarderCount = state.proxies.stream().filter(ProxyEntry::isForwarder).count();
        long upstreamCount = state.proxies.stream().filter(ProxyEntry::supportsConnect).count();
        long activeUpstreamCount = state.proxies.stream()
            .filter(proxy -> proxy.enabled && proxy.supportsConnect() && proxy.status == proxyforge.models.ProxyForgeModels.ProxyStatus.ACTIVE)
            .count();
        long totalRequests = state.proxies.stream().mapToLong(proxy -> proxy.requestsServed).sum();
        String selected = selectedProxy() == null ? "none" : selectedProxy().name;
        String burpRuleState = state.settings.manageBurpUpstreamProxy
            ? (actions.isProxyRunning() && activeUpstreamCount > 0 ? "managed-active" : "managed-inactive")
            : "manual";
        statsLabel.setText(
            "Proxy server: " + (actions.isProxyRunning() ? "running" : "stopped")
                + " | Burp rule: " + burpRuleState
                + " | Pool: " + state.proxies.size()
                + " total / " + activeCount + " enabled"
                + " | Forwarders: " + forwarderCount
                + " | Upstream proxies: " + upstreamCount
                + " | Requests served: " + totalRequests
                + " | Selected: " + selected);
    }

    public void showQuickStartWizard()
    {
        JDialog dialog = new JDialog();
        dialog.setTitle("ProxyForge Quick Start");
        dialog.setModal(false);
        JTextArea textArea = new JTextArea("""
            ProxyForge quick start

            1. Configure one provider panel with valid cloud credentials and required deployment settings.
            2. Click Deploy to create one or more provider-managed routes and add them to the pool.
            3. Enable the Burp upstream rule checkbox to let ProxyForge manage Burp's project-level upstream rule automatically.
            4. Choose a rotation strategy, then click Start / Restart Proxy.
            5. Use Validate All to health-check the pool and Rotate Now to force a different selection.

            Notes:
            - Fireprox / Flareprox forwarders are selected inside Burp and rewritten automatically for matching hosts.
            - CONNECT tunnels are handled only by standard HTTP and SOCKS5 proxy entries.
            - The Burp upstream rule is only needed when you have CONNECT-capable upstream proxies in the pool.
            - Sensitive provider fields stay in memory by default unless you enable persistence in Settings.
            """);
        textArea.setWrapStyleWord(true);
        textArea.setLineWrap(true);
        textArea.setEditable(false);
        textArea.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        dialog.add(new JScrollPane(textArea));
        dialog.setSize(new Dimension(580, 340));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
        actions.markFirstLaunchComplete();
    }

    private Component buildMainSplitPane()
    {
        JSplitPane horizontalSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        horizontalSplit.setResizeWeight(0.42);
        horizontalSplit.setLeftComponent(buildProviderArea());
        horizontalSplit.setRightComponent(buildOperationsArea());
        return horizontalSplit;
    }

    private Component buildProviderArea()
    {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("AWS", awsPanel);
        tabs.addTab("Cloudflare", cloudflarePanel);
        tabs.addTab("VPS Forge", vpsPanel);
        return tabs;
    }

    private Component buildOperationsArea()
    {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.add(buildRotationPanel(), BorderLayout.NORTH);
        JSplitPane verticalSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        verticalSplit.setResizeWeight(0.62);
        verticalSplit.setTopComponent(buildProxyPoolPanel());
        verticalSplit.setBottomComponent(buildScopeRulesPanel());
        panel.add(verticalSplit, BorderLayout.CENTER);
        return panel;
    }

    private Component buildProxyPoolPanel()
    {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Proxy Pool"));

        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton deleteSelected = new JButton("Delete Selected");
        JButton toggleSelected = new JButton("Enable / Disable");
        JButton refreshButton = new JButton("Refresh");
        topBar.add(deleteSelected);
        topBar.add(toggleSelected);
        topBar.add(refreshButton);

        deleteSelected.addActionListener(event ->
        {
            ProxyEntry selected = selectedProxy();
            if (selected == null)
            {
                showInfo("Select a proxy entry to delete.");
                return;
            }

            ProviderPanel providerPanel = switch (selected.providerType)
            {
                case AWS_FIREPROX -> awsPanel;
                case CLOUDFLARE_FLAREPROX -> cloudflarePanel;
                case VPS_FORGE -> vpsPanel;
            };
            ProviderResult result = actions.deleteProxy(selected, providerPanel.currentFields());
            showResult(result);
            refresh();
        });

        toggleSelected.addActionListener(event ->
        {
            ProxyEntry selected = selectedProxy();
            if (selected == null)
            {
                showInfo("Select a proxy entry to enable or disable.");
                return;
            }
            selected.enabled = !selected.enabled;
            actions.upsertProxy(selected);
            refresh();
        });

        refreshButton.addActionListener(event -> refresh());

        panel.add(topBar, BorderLayout.NORTH);
        panel.add(proxyTableScrollPane, BorderLayout.CENTER);
        return panel;
    }

    private Component buildRotationPanel()
    {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Rotation Engine"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("Strategy"), gbc);
        gbc.gridx = 1;
        panel.add(strategyCombo, gbc);
        gbc.gridx = 2;
        panel.add(new JLabel("Local Port"), gbc);
        gbc.gridx = 3;
        panel.add(portField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 4;
        panel.add(autoStartCheck, gbc);
        gbc.gridy = 2;
        panel.add(manageBurpUpstreamCheck, gbc);
        gbc.gridy = 3;
        panel.add(persistSensitiveCheck, gbc);
        gbc.gridy = 4;
        panel.add(externalBindCheck, gbc);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttonRow.add(restartProxyButton);
        buttonRow.add(stopProxyButton);
        buttonRow.add(rotateNowButton);
        buttonRow.add(validateAllButton);
        buttonRow.add(helpButton);

        gbc.gridy = 5;
        panel.add(buttonRow, gbc);
        gbc.gridy = 6;
        panel.add(statsLabel, gbc);
        return panel;
    }

    private Component buildScopeRulesPanel()
    {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Scope-based Routing"));

        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addRule = new JButton("Add Rule");
        JButton deleteRule = new JButton("Delete Rule");
        topBar.add(addRule);
        topBar.add(deleteRule);
        panel.add(topBar, BorderLayout.NORTH);
        panel.add(scopeRuleScrollPane, BorderLayout.CENTER);

        addRule.addActionListener(event -> addScopeRule());
        deleteRule.addActionListener(event ->
        {
            int row = scopeRuleTable.getSelectedRow();
            if (row < 0)
            {
                showInfo("Select a scope rule to remove.");
                return;
            }
            ScopeRule rule = scopeRuleTableModel.row(row);
            actions.removeScopeRule(rule.id);
            refresh();
        });
        return panel;
    }

    private Component buildFooterPanel()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Logging"));
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return panel;
    }

    private void addScopeRule()
    {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JTextField patternField = new JTextField(25);
        JCheckBox regexCheck = new JCheckBox("Regex");
        JComboBox<ProviderType> providerCombo = new JComboBox<>(ProviderType.values());
        List<ProxyEntry> proxies = new ArrayList<>(actions.currentState().proxies);
        JComboBox<String> proxyCombo = new JComboBox<>();
        proxyCombo.addItem("(provider preference only)");
        proxies.forEach(proxy -> proxyCombo.addItem(proxy.id + " :: " + proxy.name));

        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("Pattern"), gbc);
        gbc.gridx = 1;
        panel.add(patternField, gbc);
        gbc.gridx = 0;
        gbc.gridy = 1;
        panel.add(new JLabel("Provider"), gbc);
        gbc.gridx = 1;
        panel.add(providerCombo, gbc);
        gbc.gridx = 0;
        gbc.gridy = 2;
        panel.add(new JLabel("Proxy"), gbc);
        gbc.gridx = 1;
        panel.add(proxyCombo, gbc);
        gbc.gridx = 1;
        gbc.gridy = 3;
        panel.add(regexCheck, gbc);

        int choice = JOptionPane.showConfirmDialog(this, panel, "Add Scope Rule", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION)
        {
            return;
        }

        ScopeRule rule = new ScopeRule(patternField.getText(), regexCheck.isSelected(), null, (ProviderType) providerCombo.getSelectedItem());
        if (proxyCombo.getSelectedIndex() > 0)
        {
            String selected = (String) proxyCombo.getSelectedItem();
            rule.assignedProxyId = selected.split(" :: ", 2)[0];
        }
        actions.addScopeRule(rule);
        refresh();
    }

    private void applySettings(boolean restartProxy)
    {
        try
        {
            int port = Integer.parseInt(portField.getText().trim());
            actions.updateSettings(
                port,
                (RotationStrategy) strategyCombo.getSelectedItem(),
                autoStartCheck.isSelected(),
                manageBurpUpstreamCheck.isSelected(),
                persistSensitiveCheck.isSelected(),
                externalBindCheck.isSelected(),
                restartProxy);
            refresh();
        }
        catch (NumberFormatException exception)
        {
            showInfo("The local proxy port must be a valid integer.");
        }
    }

    private void showHelpDialog()
    {
        JTextArea textArea = new JTextArea("""
            ProxyForge usage

            Provider panels
            - Deploy creates a provider resource and adds the resulting proxy or forwarder to the pool.
            - List retrieves matching provider resources when live credentials are present.
            - Delete Selected removes the highlighted pool entry and triggers provider cleanup.

            Rotation engine
            - Random: choose any healthy proxy.
            - Round-Robin: cycle sequentially.
            - Least-Used: prefer the lowest served count.
            - Sticky-per-host: keep the same proxy for repeated destinations.
            - Per-scope rule: honor scope table mappings first.

            Upstream rule in Burp
            - Enable "Burp upstream rule" in ProxyForge to have the extension manage Burp's project-level upstream rule.
            - The managed rule points Burp to 127.0.0.1 on the configured local port.
            - Disable the checkbox to remove the managed rule again.
            - If the pool only contains Fireprox / Flareprox forwarders, ProxyForge leaves the rule disabled because Burp can connect directly to the rewritten worker endpoint.

            Hybrid routing
            - Fireprox / Flareprox entries are forwarders. ProxyForge rewrites matching requests to those endpoints inside Burp.
            - Standard HTTP / SOCKS5 entries are used by the local proxy for CONNECT and generic upstream traffic.
            - Scope rules still apply first. If a rule points to a forwarder, ProxyForge rewrites the request before it reaches the local proxy lane.

            Validation
            - Validate All opens a direct health check for each pool entry using the real deployed endpoint.
            - Enter target URLs with an explicit scheme such as https:// or http://.
            """);
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JOptionPane.showMessageDialog(this, new JScrollPane(textArea), "ProxyForge Help", JOptionPane.INFORMATION_MESSAGE);
    }

    private ProxyEntry selectedProxy()
    {
        int row = proxyTable.getSelectedRow();
        return row < 0 ? null : proxyTableModel.row(row);
    }

    private void showResult(ProviderResult result)
    {
        if (result == null)
        {
            return;
        }
        if (result.success())
        {
            showInfo(result.message());
        }
        else
        {
            JOptionPane.showMessageDialog(this, result.message(), "ProxyForge", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showInfo(String message)
    {
        JOptionPane.showMessageDialog(this, message, "ProxyForge", JOptionPane.INFORMATION_MESSAGE);
    }

    public interface ProxyForgeActions
    {
        ExtensionState currentState();

        ProviderResult deploy(ProviderType providerType, Map<String, String> fields);

        ProviderResult list(ProviderType providerType, Map<String, String> fields);

        ProviderResult deleteProxy(ProxyEntry proxyEntry, Map<String, String> fields);

        void upsertProxy(ProxyEntry proxyEntry);

        void updateProviderFormState(ProviderType providerType, Map<String, String> fields);

        void updateSettings(int port, RotationStrategy rotationStrategy, boolean autoStart, boolean manageBurpUpstream, boolean persistSensitive, boolean allowExternalBind, boolean restartProxy);

        void stopProxy();

        void rotateNow();

        void validateAll();

        void addScopeRule(ScopeRule scopeRule);

        void removeScopeRule(String id);

        void markFirstLaunchComplete();

        boolean isProxyRunning();
    }

    private static final class ProviderPanel extends JPanel
    {
        private final ProviderType providerType;
        private final List<FieldSpec> fieldSpecs;
        private final Map<String, JTextField> fields = new LinkedHashMap<>();
        private ProxyForgeActions actions;
        private Supplier<ProxyEntry> selectedProxySupplier;

        private ProviderPanel(ProviderType providerType, List<FieldSpec> fieldSpecs)
        {
            super(new BorderLayout(8, 8));
            this.providerType = providerType;
            this.fieldSpecs = fieldSpecs;
            setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

            JPanel formPanel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(4, 4, 4, 4);
            gbc.anchor = GridBagConstraints.WEST;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;

            int row = 0;
            for (FieldSpec spec : fieldSpecs)
            {
                gbc.gridx = 0;
                gbc.gridy = row;
                gbc.weightx = 0.0;
                formPanel.add(new JLabel(spec.label()), gbc);

                gbc.gridx = 1;
                gbc.weightx = 1.0;
                JTextField textField = new JTextField();
                textField.setColumns(24);
                fields.put(spec.key(), textField);
                formPanel.add(textField, gbc);
                row++;
            }

            JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
            JButton deployButton = new JButton("Deploy");
            JButton listButton = new JButton("List");
            JButton deleteButton = new JButton("Delete Selected");
            buttonBar.add(deployButton);
            buttonBar.add(listButton);
            buttonBar.add(deleteButton);

            add(formPanel, BorderLayout.CENTER);
            add(buttonBar, BorderLayout.SOUTH);

            deployButton.addActionListener(event ->
            {
                rememberState();
                ProviderResult result = actions.deploy(providerType, currentFields());
                result.proxies().forEach(actions::upsertProxy);
                if (result.proxy() != null && result.proxies().isEmpty())
                {
                    actions.upsertProxy(result.proxy());
                }
                JOptionPane.showMessageDialog(this, result.message(), "ProxyForge", result.success() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE);
            });

            listButton.addActionListener(event ->
            {
                rememberState();
                ProviderResult result = actions.list(providerType, currentFields());
                if (result.success())
                {
                    result.proxies().forEach(actions::upsertProxy);
                }
                JOptionPane.showMessageDialog(this, result.message(), "ProxyForge", result.success() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE);
            });

            deleteButton.addActionListener(event ->
            {
                ProxyEntry selected = selectedProxySupplier.get();
                if (selected == null)
                {
                    JOptionPane.showMessageDialog(this, "Select a matching proxy from the pool first.", "ProxyForge", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                if (selected.providerType != providerType)
                {
                    JOptionPane.showMessageDialog(this, "The selected pool entry belongs to another provider.", "ProxyForge", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                rememberState();
                ProviderResult result = actions.deleteProxy(selected, currentFields());
                JOptionPane.showMessageDialog(this, result.message(), "ProxyForge", result.success() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE);
            });
        }

        private void attach(ProxyForgeActions actions, Supplier<ProxyEntry> selectedProxySupplier)
        {
            this.actions = actions;
            this.selectedProxySupplier = selectedProxySupplier;
        }

        private void load(ExtensionState state)
        {
            if (actions == null)
            {
                return;
            }

            ProxyForgeModels.ProviderFormState formState = state.providerFormStates.get(providerType);
            if (formState == null)
            {
                fields.forEach((key, component) -> component.setText(defaultValue(providerType, key)));
                return;
            }

            fields.forEach((key, component) -> component.setText(formState.fields.getOrDefault(key, defaultValue(providerType, key))));
        }

        private Map<String, String> currentFields()
        {
            Map<String, String> current = new LinkedHashMap<>();
            fields.forEach((key, component) -> current.put(key, component.getText().trim()));
            return current;
        }

        private void rememberState()
        {
            actions.updateProviderFormState(providerType, currentFields());
        }

        private static String defaultValue(ProviderType providerType, String key)
        {
            return switch (providerType)
            {
                case AWS_FIREPROX -> "region".equals(key) ? "us-east-1" : "";
                case CLOUDFLARE_FLAREPROX -> "";
                case VPS_FORGE -> switch (key)
                {
                    case "vendor" -> "DigitalOcean";
                    case "region" -> "nyc1";
                    case "instanceType" -> "s-1vcpu-1gb";
                    default -> "";
                };
            };
        }
    }

    private record FieldSpec(String key, String label, boolean sensitive)
    {
    }

    private static final class ProxyTableModel extends AbstractTableModel
    {
        private final String[] columns = {"Status", "Provider", "Route", "CONNECT", "Target", "Endpoint", "Requests", "Last Error"};
        private List<ProxyEntry> rows = List.of();

        @Override
        public int getRowCount()
        {
            return rows.size();
        }

        @Override
        public int getColumnCount()
        {
            return columns.length;
        }

        @Override
        public String getColumnName(int column)
        {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex)
        {
            ProxyEntry row = rows.get(rowIndex);
            return switch (columnIndex)
            {
                case 0 -> row.status;
                case 1 -> row.providerType.label();
                case 2 -> row.isForwarder() ? "Forwarder rewrite" : row.proxyMode.label();
                case 3 -> row.supportsConnect() ? "Yes" : "No";
                case 4 -> row.isForwarder() ? row.targetBaseUrl : "";
                case 5 -> row.displayEndpoint();
                case 6 -> row.requestsServed;
                case 7 -> row.lastError;
                default -> "";
            };
        }

        public void setRows(List<ProxyEntry> rows)
        {
            this.rows = List.copyOf(rows);
            fireTableDataChanged();
        }

        public ProxyEntry row(int rowIndex)
        {
            return rows.get(rowIndex);
        }

        public int indexOfProxyId(String proxyId)
        {
            for (int index = 0; index < rows.size(); index++)
            {
                if (rows.get(index).id.equals(proxyId))
                {
                    return index;
                }
            }
            return -1;
        }
    }

    private static final class ScopeRuleTableModel extends AbstractTableModel
    {
        private final String[] columns = {"Pattern", "Regex", "Proxy ID", "Provider"};
        private List<ScopeRule> rows = List.of();

        @Override
        public int getRowCount()
        {
            return rows.size();
        }

        @Override
        public int getColumnCount()
        {
            return columns.length;
        }

        @Override
        public String getColumnName(int column)
        {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex)
        {
            ScopeRule row = rows.get(rowIndex);
            return switch (columnIndex)
            {
                case 0 -> row.pattern;
                case 1 -> row.regex;
                case 2 -> row.assignedProxyId;
                case 3 -> row.preferredProvider == null ? "" : row.preferredProvider.label();
                default -> "";
            };
        }

        public void setRows(List<ScopeRule> rows)
        {
            this.rows = List.copyOf(rows);
            fireTableDataChanged();
        }

        public ScopeRule row(int rowIndex)
        {
            return rows.get(rowIndex);
        }
    }

}
