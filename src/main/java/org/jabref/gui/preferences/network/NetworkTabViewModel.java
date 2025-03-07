package org.jabref.gui.preferences.network;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ListProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleListProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.stage.FileChooser;

import org.jabref.gui.DialogService;
import org.jabref.gui.Globals;
import org.jabref.gui.preferences.PreferenceTabViewModel;
import org.jabref.gui.remote.JabRefMessageHandler;
import org.jabref.gui.util.FileDialogConfiguration;
import org.jabref.logic.l10n.Localization;
import org.jabref.logic.net.ProxyPreferences;
import org.jabref.logic.net.ProxyRegisterer;
import org.jabref.logic.net.URLDownload;
import org.jabref.logic.net.ssl.SSLCertificate;
import org.jabref.logic.net.ssl.SSLPreferences;
import org.jabref.logic.net.ssl.TrustStoreManager;
import org.jabref.logic.remote.RemotePreferences;
import org.jabref.logic.remote.RemoteUtil;
import org.jabref.logic.util.StandardFileType;
import org.jabref.model.strings.StringUtil;
import org.jabref.preferences.PreferencesService;

import de.saxsys.mvvmfx.utils.validation.CompositeValidator;
import de.saxsys.mvvmfx.utils.validation.FunctionBasedValidator;
import de.saxsys.mvvmfx.utils.validation.ValidationMessage;
import de.saxsys.mvvmfx.utils.validation.ValidationStatus;
import de.saxsys.mvvmfx.utils.validation.Validator;
import kong.unirest.UnirestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NetworkTabViewModel implements PreferenceTabViewModel {
    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkTabViewModel.class);

    private final BooleanProperty remoteServerProperty = new SimpleBooleanProperty();
    private final StringProperty remotePortProperty = new SimpleStringProperty("");
    private final BooleanProperty proxyUseProperty = new SimpleBooleanProperty();
    private final StringProperty proxyHostnameProperty = new SimpleStringProperty("");
    private final StringProperty proxyPortProperty = new SimpleStringProperty("");
    private final BooleanProperty proxyUseAuthenticationProperty = new SimpleBooleanProperty();
    private final StringProperty proxyUsernameProperty = new SimpleStringProperty("");
    private final StringProperty proxyPasswordProperty = new SimpleStringProperty("");
    private final ListProperty<CustomCertificateViewModel> customCertificateListProperty = new SimpleListProperty<>(FXCollections.observableArrayList());

    private final Validator remotePortValidator;
    private final Validator proxyHostnameValidator;
    private final Validator proxyPortValidator;
    private final Validator proxyUsernameValidator;
    private final Validator proxyPasswordValidator;

    private final DialogService dialogService;
    private final PreferencesService preferences;
    private final RemotePreferences remotePreferences;
    private final ProxyPreferences proxyPreferences;
    private final ProxyPreferences backupProxyPreferences;
    private final SSLPreferences sslPreferences;

    private final List<String> restartWarning = new ArrayList<>();

    private final TrustStoreManager trustStoreManager;

    public NetworkTabViewModel(DialogService dialogService, PreferencesService preferences) {
        this.dialogService = dialogService;
        this.preferences = preferences;
        this.remotePreferences = preferences.getRemotePreferences();
        this.proxyPreferences = preferences.getProxyPreferences();
        this.sslPreferences = preferences.getSSLPreferences();

        backupProxyPreferences = new ProxyPreferences(
                proxyPreferences.shouldUseProxy(),
                proxyPreferences.getHostname(),
                proxyPreferences.getPort(),
                proxyPreferences.shouldUseAuthentication(),
                proxyPreferences.getUsername(),
                proxyPreferences.getPassword());

        remotePortValidator = new FunctionBasedValidator<>(
                remotePortProperty,
                input -> {
                    try {
                        int portNumber = Integer.parseInt(remotePortProperty().getValue());
                        return RemoteUtil.isUserPort(portNumber);
                    } catch (NumberFormatException ex) {
                        return false;
                    }
                },
                ValidationMessage.error(String.format("%s > %s %n %n %s",
                        Localization.lang("Network"),
                        Localization.lang("Remote operation"),
                        Localization.lang("You must enter an integer value in the interval 1025-65535"))));

        proxyHostnameValidator = new FunctionBasedValidator<>(
                proxyHostnameProperty,
                input -> !StringUtil.isNullOrEmpty(input),
                ValidationMessage.error(String.format("%s > %s %n %n %s",
                        Localization.lang("Network"),
                        Localization.lang("Proxy configuration"),
                        Localization.lang("Please specify a hostname"))));

        proxyPortValidator = new FunctionBasedValidator<>(
                proxyPortProperty,
                input -> getPortAsInt(input).isPresent(),
                ValidationMessage.error(String.format("%s > %s %n %n %s",
                        Localization.lang("Network"),
                        Localization.lang("Proxy configuration"),
                        Localization.lang("Please specify a port"))));

        proxyUsernameValidator = new FunctionBasedValidator<>(
                proxyUsernameProperty,
                input -> !StringUtil.isNullOrEmpty(input),
                ValidationMessage.error(String.format("%s > %s %n %n %s",
                        Localization.lang("Network"),
                        Localization.lang("Proxy configuration"),
                        Localization.lang("Please specify a username"))));

        proxyPasswordValidator = new FunctionBasedValidator<>(
                proxyPasswordProperty,
                input -> input.length() > 0,
                ValidationMessage.error(String.format("%s > %s %n %n %s",
                        Localization.lang("Network"),
                        Localization.lang("Proxy configuration"),
                        Localization.lang("Please specify a password"))));
        this.trustStoreManager = new TrustStoreManager(Path.of(sslPreferences.getTruststorePath()));
    }

    public void setValues() {
        remoteServerProperty.setValue(remotePreferences.useRemoteServer());
        remotePortProperty.setValue(String.valueOf(remotePreferences.getPort()));

        setProxyValues();
        setSSLValues();
    }

    private void setProxyValues() {
        proxyUseProperty.setValue(proxyPreferences.shouldUseProxy());
        proxyHostnameProperty.setValue(proxyPreferences.getHostname());
        proxyPortProperty.setValue(proxyPreferences.getPort());
        proxyUseAuthenticationProperty.setValue(proxyPreferences.shouldUseAuthentication());
        proxyUsernameProperty.setValue(proxyPreferences.getUsername());
        proxyPasswordProperty.setValue(proxyPreferences.getPassword());
    }

    private void setSSLValues() {
        customCertificateListProperty.clear();

        trustStoreManager.getCustomCertificates().forEach(cert -> customCertificateListProperty.add(CustomCertificateViewModel.fromSSLCertificate(cert)));
        customCertificateListProperty.addListener((ListChangeListener<CustomCertificateViewModel>) c -> {
            while (c.next()) {
                if (c.wasAdded()) {
                    CustomCertificateViewModel certificate = c.getAddedSubList().get(0);
                    certificate.getPath().ifPresent(path -> trustStoreManager
                            .addCertificate(formatCustomAlias(certificate.getThumbprint()), Path.of(path)));
                } else if (c.wasRemoved()) {
                    CustomCertificateViewModel certificate = c.getRemoved().get(0);
                    trustStoreManager.deleteCertificate(formatCustomAlias(certificate.getThumbprint()));
                }
            }
        });
    }

    public void storeSettings() {
        storeRemoteSettings();

        storeProxySettings(new ProxyPreferences(
                proxyUseProperty.getValue(),
                proxyHostnameProperty.getValue().trim(),
                proxyPortProperty.getValue().trim(),
                proxyUseAuthenticationProperty.getValue(),
                proxyUsernameProperty.getValue().trim(),
                proxyPasswordProperty.getValue()
        ));
        storeSSLSettings();
    }

    private void storeRemoteSettings() {
        RemotePreferences newRemotePreferences = new RemotePreferences(
                remotePreferences.getPort(),
                remoteServerProperty.getValue()
        );

        getPortAsInt(remotePortProperty.getValue()).ifPresent(newPort -> {
            if (remotePreferences.isDifferentPort(newPort)) {
                remotePreferences.setPort(newPort);
            }
        });

        if (remoteServerProperty.getValue()) {
            remotePreferences.setUseRemoteServer(true);
            Globals.REMOTE_LISTENER.openAndStart(new JabRefMessageHandler(), remotePreferences.getPort(), preferences);
        } else {
            remotePreferences.setUseRemoteServer(false);
            Globals.REMOTE_LISTENER.stop();
        }
    }

    private void storeProxySettings(ProxyPreferences newProxyPreferences) {
        if (!newProxyPreferences.equals(proxyPreferences)) {
            ProxyRegisterer.register(newProxyPreferences);
        }

        proxyPreferences.setUseProxy(newProxyPreferences.shouldUseProxy());
        proxyPreferences.setHostname(newProxyPreferences.getHostname());
        proxyPreferences.setPort(newProxyPreferences.getPort());
        proxyPreferences.setUseAuthentication(newProxyPreferences.shouldUseAuthentication());
        proxyPreferences.setUsername(newProxyPreferences.getUsername());
        proxyPreferences.setPassword(newProxyPreferences.getPassword());
    }

    public void storeSSLSettings() {
        trustStoreManager.flush();
    }

    private Optional<Integer> getPortAsInt(String value) {
        try {
            return Optional.of(Integer.parseInt(value));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    public ValidationStatus remotePortValidationStatus() {
        return remotePortValidator.getValidationStatus();
    }

    public ValidationStatus proxyHostnameValidationStatus() {
        return proxyHostnameValidator.getValidationStatus();
    }

    public ValidationStatus proxyPortValidationStatus() {
        return proxyPortValidator.getValidationStatus();
    }

    public ValidationStatus proxyUsernameValidationStatus() {
        return proxyUsernameValidator.getValidationStatus();
    }

    public ValidationStatus proxyPasswordValidationStatus() {
        return proxyPasswordValidator.getValidationStatus();
    }

    public boolean validateSettings() {
        CompositeValidator validator = new CompositeValidator();

        if (remoteServerProperty.getValue()) {
            validator.addValidators(remotePortValidator);
        }

        if (proxyUseProperty.getValue()) {
            validator.addValidators(proxyHostnameValidator);
            validator.addValidators(proxyPortValidator);

            if (proxyUseAuthenticationProperty.getValue()) {
                validator.addValidators(proxyUsernameValidator);
                validator.addValidators(proxyPasswordValidator);
            }
        }

        ValidationStatus validationStatus = validator.getValidationStatus();
        if (!validationStatus.isValid()) {
            validationStatus.getHighestMessage().ifPresent(message ->
                    dialogService.showErrorDialogAndWait(message.getMessage()));
            return false;
        }
        return true;
    }

    /**
     * Check the connection by using the given url. Used for validating the http proxy. The checking result will be appear when request finished. The checking result could be either success or fail, if fail, the cause will be displayed.
     */
    public void checkConnection() {
        final String connectionSuccessText = Localization.lang("Connection successful!");
        final String connectionFailedText = Localization.lang("Connection failed!");
        final String dialogTitle = Localization.lang("Check Proxy Setting");

        final String testUrl = "http://jabref.org";

        // Workaround for testing, since the URLDownload uses stored proxy settings, see
        // preferences.storeProxyPreferences(...) below.
        storeProxySettings(new ProxyPreferences(
                proxyUseProperty.getValue(),
                proxyHostnameProperty.getValue().trim(),
                proxyPortProperty.getValue().trim(),
                proxyUseAuthenticationProperty.getValue(),
                proxyUsernameProperty.getValue().trim(),
                proxyPasswordProperty.getValue()
        ));

        URLDownload urlDownload;
        try {
            urlDownload = new URLDownload(testUrl);
            if (urlDownload.canBeReached()) {
                dialogService.showInformationDialogAndWait(dialogTitle, connectionSuccessText);
            } else {
                dialogService.showErrorDialogAndWait(dialogTitle, connectionFailedText);
            }
        } catch (MalformedURLException e) {
            // Why would that happen? Because one of developers inserted a failing url in testUrl...
        } catch (UnirestException e) {
            dialogService.showErrorDialogAndWait(dialogTitle, connectionFailedText);
        }

        storeProxySettings(backupProxyPreferences);
    }

    @Override
    public List<String> getRestartWarnings() {
        return restartWarning;
    }

    public BooleanProperty remoteServerProperty() {
        return remoteServerProperty;
    }

    public StringProperty remotePortProperty() {
        return remotePortProperty;
    }

    public BooleanProperty proxyUseProperty() {
        return proxyUseProperty;
    }

    public StringProperty proxyHostnameProperty() {
        return proxyHostnameProperty;
    }

    public StringProperty proxyPortProperty() {
        return proxyPortProperty;
    }

    public BooleanProperty proxyUseAuthenticationProperty() {
        return proxyUseAuthenticationProperty;
    }

    public StringProperty proxyUsernameProperty() {
        return proxyUsernameProperty;
    }

    public StringProperty proxyPasswordProperty() {
        return proxyPasswordProperty;
    }

    public ListProperty<CustomCertificateViewModel> customCertificateListProperty() {
        return customCertificateListProperty;
    }

    public void addCertificateFile() {
        FileDialogConfiguration fileDialogConfiguration = new FileDialogConfiguration.Builder()
                .addExtensionFilter(new FileChooser.ExtensionFilter(Localization.lang("SSL certificate file"), "*.crt", "*.cer"))
                .withDefaultExtension(Localization.lang("SSL certificate file"), StandardFileType.CER)
                .withInitialDirectory(preferences.getFilePreferences().getWorkingDirectory())
                .build();

        dialogService.showFileOpenDialog(fileDialogConfiguration).ifPresent(certPath -> SSLCertificate.fromPath(certPath).ifPresent(sslCertificate -> {
            if (!trustStoreManager.isCertificateExist(formatCustomAlias(sslCertificate.getSHA256Thumbprint()))) {
                customCertificateListProperty.add(CustomCertificateViewModel.fromSSLCertificate(sslCertificate)
                                                                            .setPath(certPath.toAbsolutePath().toString()));
            } else {
                dialogService.showWarningDialogAndWait(Localization.lang("Duplicate Certificates"), Localization.lang("You already added this certificate"));
            }
        }));
    }

    private String formatCustomAlias(String thumbprint) {
        return String.format("%s[custom]", thumbprint);
    }
}
