package org.keycloak.adapters.tomcat7;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Logger;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

import org.apache.catalina.Context;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.authenticator.FormAuthenticator;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.deploy.LoginConfig;
import org.keycloak.KeycloakSecurityContext;
import org.keycloak.adapters.AdapterConstants;
import org.keycloak.adapters.AdapterDeploymentContext;
import org.keycloak.adapters.AuthChallenge;
import org.keycloak.adapters.AuthOutcome;
import org.keycloak.adapters.HttpFacade;
import org.keycloak.adapters.KeycloakDeployment;
import org.keycloak.adapters.KeycloakDeploymentBuilder;
import org.keycloak.adapters.PreAuthActionsHandler;
import org.keycloak.adapters.RefreshableKeycloakSecurityContext;

/**
 * Web deployment whose security is managed by a remote OAuth Skeleton Key
 * authentication server
 * <p/>
 * Redirects browser to remote authentication server if not logged in. Also
 * allows OAuth Bearer Token requests that contain a Skeleton Key bearer tokens.
 * 
 * @author <a href="mailto:ungarida@gmail.com">Davide Ungari</a>
 * @version $Revision: 1 $
 */
public class KeycloakAuthenticatorValve extends FormAuthenticator implements LifecycleListener {
	private final static Logger log = Logger.getLogger(""+KeycloakAuthenticatorValve.class);
	protected CatalinaUserSessionManagement userSessionManagement = new CatalinaUserSessionManagement();
    protected AdapterDeploymentContext deploymentContext;

    @Override
    public void lifecycleEvent(LifecycleEvent event) {
        if (event.getType() == Lifecycle.START_EVENT) {
            try {
                startDeployment();
            } catch (LifecycleException e) {
                e.printStackTrace();
            }
        }
    }

    public void startDeployment() throws LifecycleException {
        super.start();
        StandardContext standardContext = (StandardContext) context;
        standardContext.addLifecycleListener(this);
        cache = false;
    }

    public void initInternal() {
        InputStream configInputStream = getConfigInputStream(context);
        KeycloakDeployment kd = null;
        if (configInputStream == null) {
            log.warning("No adapter configuration.  Keycloak is unconfigured and will deny all requests.");
            kd = new KeycloakDeployment();
        } else {
            kd = KeycloakDeploymentBuilder.build(configInputStream);
        }
        deploymentContext = new AdapterDeploymentContext(kd);
        context.getServletContext().setAttribute(AdapterDeploymentContext.class.getName(), deploymentContext);
        AuthenticatedActionsValve actions = new AuthenticatedActionsValve(deploymentContext, getNext(), getContainer(), getObjectName());
        setNext(actions);
    }

    private static InputStream getJSONFromServletContext(ServletContext servletContext) {
        String json = servletContext.getInitParameter(AdapterConstants.AUTH_DATA_PARAM_NAME);
        if (json == null) {
            return null;
        }
        log.info("**** using " + AdapterConstants.AUTH_DATA_PARAM_NAME);
        log.info(json);
        return new ByteArrayInputStream(json.getBytes());
    }

    private static InputStream getConfigInputStream(Context context) {
        InputStream is = getJSONFromServletContext(context.getServletContext());
        if (is == null) {
            String path = context.getServletContext().getInitParameter("keycloak.config.file");
            if (path == null) {
                log.info("**** using /WEB-INF/keycloak.json");
                is = context.getServletContext().getResourceAsStream("/WEB-INF/keycloak.json");
            } else {
                try {
                    is = new FileInputStream(path);
                } catch (FileNotFoundException e) {
                	log.severe("NOT FOUND /WEB-INF/keycloak.json");
                    throw new RuntimeException(e);
                }
            }
        }
        return is;
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        try {
            CatalinaHttpFacade facade = new CatalinaHttpFacade(request, response);
            PreAuthActionsHandler handler = new PreAuthActionsHandler(userSessionManagement, deploymentContext, facade);
            if (handler.handleRequest()) {
                return;
            }
            checkKeycloakSession(request, facade);
            super.invoke(request, response);
        } finally {
        }
    }

    @Override
    public boolean authenticate(Request request, HttpServletResponse response, LoginConfig config) throws IOException {
        CatalinaHttpFacade facade = new CatalinaHttpFacade(request, response);
        KeycloakDeployment deployment = deploymentContext.resolveDeployment(facade);
        if (deployment == null || !deployment.isConfigured()) {
            return false;
        }

        CatalinaRequestAuthenticator authenticator = new CatalinaRequestAuthenticator(deployment, this, userSessionManagement, facade, request);
        AuthOutcome outcome = authenticator.authenticate();
        if (outcome == AuthOutcome.AUTHENTICATED) {
            if (facade.isEnded()) {
                return false;
            }
            return true;
        }
        AuthChallenge challenge = authenticator.getChallenge();
        if (challenge != null) {
            challenge.challenge(facade);
        }
        return false;
    }

    /**
     * Checks that access token is still valid.  Will attempt refresh of token if it is not.
     *
     * @param request
     */
    protected void checkKeycloakSession(Request request, HttpFacade facade) {
        if (request.getSessionInternal(false) == null || request.getSessionInternal().getPrincipal() == null) return;
        RefreshableKeycloakSecurityContext session = (RefreshableKeycloakSecurityContext) request.getSessionInternal().getNote(KeycloakSecurityContext.class.getName());
        if (session == null) return;
        // just in case session got serialized
        if (session.getDeployment() == null) session.setDeployment(deploymentContext.resolveDeployment(facade));
        if (session.isActive()) return;

        // FYI: A refresh requires same scope, so same roles will be set.  Otherwise, refresh will fail and token will
        // not be updated
        session.refreshExpiredToken();
        if (session.isActive()) return;

        request.getSessionInternal().removeNote(KeycloakSecurityContext.class.getName());
        request.setUserPrincipal(null);
        request.setAuthType(null);
        request.getSessionInternal().setPrincipal(null);
        request.getSessionInternal().setAuthType(null);
    }

    public void keycloakSaveRequest(Request request) throws IOException {
        saveRequest(request, request.getSessionInternal(true));
    }

    public boolean keycloakRestoreRequest(Request request) {
        try {
            return restoreRequest(request, request.getSessionInternal());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
