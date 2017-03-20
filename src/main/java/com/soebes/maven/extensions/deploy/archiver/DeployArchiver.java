package com.soebes.maven.extensions.deploy.archiver;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.IOException;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.ExecutionEvent.Type;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.artifact.install.ArtifactInstallerException;
import org.apache.maven.shared.project.NoFileAssignedException;
import org.apache.maven.shared.project.deploy.ProjectDeployer;
import org.apache.maven.shared.project.deploy.ProjectDeployerRequest;
import org.apache.maven.shared.project.install.ProjectInstaller;
import org.apache.maven.shared.project.install.ProjectInstallerRequest;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.tar.TarArchiver;
import org.codehaus.plexus.archiver.tar.TarArchiver.TarCompressionMethod;
import org.codehaus.plexus.archiver.tar.TarLongFileMode;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositoryEvent.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * 
 * @author Karl Heinz Marbaise <a href="mailto:khmarbaise@apache.org">khmarbaise@apache.org</a>
 */
@Singleton
@Named
public class DeployArchiver
    extends AbstractEventSpy
{
    private final Logger LOGGER = LoggerFactory.getLogger( getClass() );

    @Inject
    private ProjectDeployer projectDeployer;

    @Inject
    private ProjectInstaller projectInstaller;

    /**
     * The JAR archiver needed for archiving the environments.
     */
    @Inject
    private TarArchiver tarArchiver;

    private boolean failure;

    public DeployArchiver()
    {
        this.failure = false;
    }

    @Override
    public void init( Context context )
        throws Exception
    {
        super.init( context );
        logDeployerVersion();
    }

    private void logDeployerVersion()
    {
        LOGGER.info( "" );
        LOGGER.info( " --- deploy-archiver-extension:{} --- ", MavenDeployerExtensionVersion.getVersion() );
    }

    @Override
    public void onEvent( Object event )
        throws Exception
    {
        try
        {
            // We are only interested in the ExecutionEvent.
            if ( event instanceof ExecutionEvent )
            {
                executionEventHandler( (ExecutionEvent) event );
            }
            else if ( event instanceof org.eclipse.aether.RepositoryEvent )
            {
                repositoryEventHandler( (org.eclipse.aether.RepositoryEvent) event );
            }
        }
        catch ( Exception e )
        {
            LOGGER.error( "Exception", e );
        }
    }

    private void artifactInstalled( RepositoryEvent event )
    {
        LOGGER.info( "artifactInstalled: " + event.getFile() +  " Name: " + event.getFile().getName() );
        tarArchiver.addFile( event.getFile(), event.getArtifact().getGroupId() + "/" + event.getFile().getName() );
    }
    private void artifactInstalledMetadata( RepositoryEvent event )
    {
        event.getSession().getLocalRepository().getBasedir();
        LOGGER.info( "artifactInstalledMetadata: " + event.getFile() +  " Name: " + event.getFile().getName() );
        tarArchiver.addFile( event.getFile(), event.getMetadata().getGroupId() + "/" + event.getFile().getName() );
    }

    private void repositoryEventHandler( org.eclipse.aether.RepositoryEvent repositoryEvent )
    {
        EventType type = repositoryEvent.getType();
        switch ( type )
        {
            case ARTIFACT_DOWNLOADING:
            case ARTIFACT_DOWNLOADED:
                break;

            case ARTIFACT_DEPLOYING:
            case ARTIFACT_DEPLOYED:
                break;

            case ARTIFACT_INSTALLING:
                break;
            case ARTIFACT_INSTALLED:
                artifactInstalled( repositoryEvent );
                // THIS IS THE ONE Which is interesting...
                break;

            case METADATA_DEPLOYING:
            case METADATA_DEPLOYED:
                break;

            case METADATA_DOWNLOADING:
            case METADATA_DOWNLOADED:
                break;

            case METADATA_INSTALLING:
                break;
            case METADATA_INSTALLED:
                // I'M not sure if this is really needed.
                // artifactInstalledMetadata( repositoryEvent );
                break;

            case ARTIFACT_RESOLVING:
            case ARTIFACT_RESOLVED:
            case ARTIFACT_DESCRIPTOR_INVALID:
            case ARTIFACT_DESCRIPTOR_MISSING:
            case METADATA_RESOLVED:
            case METADATA_RESOLVING:
            case METADATA_INVALID:
                // Those events are not recorded.
                break;

            default:
                LOGGER.error( "repositoryEventHandler {}", type );
                break;
        }
    }

    @Override
    public void close()
    {
        // TODO: Check if we need to do something here?
        LOGGER.debug( "Deploy Archiver Extension." );
    }

    private boolean goalsContain( ExecutionEvent executionEvent, String goal )
    {
        return executionEvent.getSession().getGoals().contains( goal );
    }

    private void executionEventHandler( ExecutionEvent executionEvent )
    {
        Type type = executionEvent.getType();
        switch ( type )
        {
            case ProjectDiscoveryStarted:
                break;
            case SessionStarted:
                sessionStarted( executionEvent );
                break;
            case SessionEnded:
                if ( this.failure )
                {
                    LOGGER.warn( "The Deploye Archiver Extension will not be called based on previous errors." );
                }
                else
                {
                    sessionEnded( executionEvent );
                }
                break;
            case ForkFailed:
            case ForkedProjectFailed:
            case MojoFailed:
            case ProjectFailed:
                // TODO: Can we find out more about the cause of failure?
                LOGGER.debug( "Some failure has occured." );
                this.failure = true;
                break;

            case ForkStarted:
            case ForkSucceeded:
            case ForkedProjectStarted:
            case ForkedProjectSucceeded:
            case MojoStarted:
            case MojoSucceeded:
            case MojoSkipped:
            case ProjectStarted:
            case ProjectSucceeded:
            case ProjectSkipped:
                break;

            default:
                LOGGER.error( "executionEventHandler: {}", type );
                break;
        }

    }

    /**
     * This will start to deploy all artifacts into remote repository if the goal {@code deploy} has been called.
     * 
     * @param executionEvent
     */
    private void sessionEnded( ExecutionEvent executionEvent )
    {
        logDeployerVersion();

        LOGGER.info( "" );
        LOGGER.info( "Packaging installable artifacts..." );

        installProjects( executionEvent );
        
//        if ( goalsContain( executionEvent, "deploy" ) )
//        {
//            LOGGER.info( "" );
//            LOGGER.info( "Deploying artifacts..." );
//            deployProjects( executionEvent );
//        }
//        else
//        {
//            LOGGER.info( " skipping." );
//        }
    }

    private void sessionStarted( ExecutionEvent executionEvent )
    {
        if ( containsLifeCycleDeployPluginGoal( executionEvent, "deploy" ) )
        {
            removeDeployPluginFromLifeCycle( executionEvent );
        }

        if ( containsLifeCycleInstallPluginGoal( executionEvent, "install" ) )
        {
            removeInstallPluginFromLifeCycle( executionEvent );
        }
    }

    private boolean containsLifeCycleDeployPluginGoal( ExecutionEvent executionEvent, String goal )
    {
        return containsLifeCyclePluginGoals( executionEvent, "org.apache.maven.plugins", "maven-deploy-plugin", goal );
    }

    private boolean containsLifeCycleInstallPluginGoal( ExecutionEvent executionEvent, String goal )
    {
        return containsLifeCyclePluginGoals( executionEvent, "org.apache.maven.plugins", "maven-install-plugin", goal );
    }

    private void removeDeployPluginFromLifeCycle( ExecutionEvent executionEvent )
    {
        removePluginFromLifeCycle( executionEvent, "org.apache.maven.plugins", "maven-deploy-plugin", "deploy" );
    }

    private void removeInstallPluginFromLifeCycle( ExecutionEvent executionEvent )
    {
        removePluginFromLifeCycle( executionEvent, "org.apache.maven.plugins", "maven-install-plugin", "install" );
    }

    private boolean containsLifeCyclePluginGoals( ExecutionEvent executionEvent, String groupId, String artifactId,
                                                  String goal )
    {

        boolean result = false;
        List<MavenProject> sortedProjects = executionEvent.getSession().getProjectDependencyGraph().getSortedProjects();
        for ( MavenProject mavenProject : sortedProjects )
        {
            List<Plugin> buildPlugins = mavenProject.getBuildPlugins();
            for ( Plugin plugin : buildPlugins )
            {
                if ( groupId.equals( plugin.getGroupId() ) && artifactId.equals( plugin.getArtifactId() ) )
                {
                    List<PluginExecution> executions = plugin.getExecutions();
                    for ( PluginExecution pluginExecution : executions )
                    {
                        if ( pluginExecution.getGoals().contains( goal ) )
                        {
                            result = true;
                        }
                    }
                }
            }
        }
        return result;
    }

    private void removePluginFromLifeCycle( ExecutionEvent executionEvent, String groupId, String artifactId,
                                            String goal )
    {

        boolean removed = false;

        List<MavenProject> sortedProjects = executionEvent.getSession().getProjectDependencyGraph().getSortedProjects();
        for ( MavenProject mavenProject : sortedProjects )
        {
            List<Plugin> buildPlugins = mavenProject.getBuildPlugins();
            for ( Plugin plugin : buildPlugins )
            {
                LOGGER.debug( "Plugin: " + plugin.getId() );
                List<PluginExecution> printExecutions = plugin.getExecutions();
                for ( PluginExecution pluginExecution : printExecutions )
                {
                    LOGGER.debug( "  -> " + pluginExecution.getGoals() );
                }

                if ( groupId.equals( plugin.getGroupId() ) && artifactId.equals( plugin.getArtifactId() ) )
                {
                    if ( !removed )
                    {
                        LOGGER.warn( groupId + ":" + artifactId + ":" + goal + " has been deactivated." );
                    }
                    List<PluginExecution> executions = plugin.getExecutions();
                    for ( PluginExecution pluginExecution : executions )
                    {
                        pluginExecution.removeGoal( goal );
                        removed = true;
                    }
                }
            }
        }
    }

//    private void deployProjects( ExecutionEvent executionEvent )
//    {
//        // Assumption is to have the distributionManagement in the top level
//        // pom file located.
//        ArtifactRepository repository =
//            executionEvent.getSession().getTopLevelProject().getDistributionManagementArtifactRepository();
//
//        List<MavenProject> sortedProjects = executionEvent.getSession().getProjectDependencyGraph().getSortedProjects();
//        for ( MavenProject mavenProject : sortedProjects )
//        {
//            ProjectDeployerRequest deployRequest =
//                new ProjectDeployerRequest().setProject( mavenProject ).setUpdateReleaseInfo( true );
//
//            deployProject( executionEvent.getSession().getProjectBuildingRequest(), deployRequest, repository );
//        }
//    }
//
    private void installProjects( ExecutionEvent exec )
    {

        // tarArchiver.addFileSet( new DefaultFileSet( targetDirectory ) );

        File resultArchive =
            getArchiveFile( exec.getSession().getTopLevelProject().getBasedir(), "archive", "test", "tar" );

        tarArchiver.setDestFile( resultArchive );
        tarArchiver.setCompression( TarCompressionMethod.none );
        tarArchiver.setLongfile( TarLongFileMode.posix );

        List<MavenProject> sortedProjects = exec.getSession().getProjectDependencyGraph().getSortedProjects();
        for ( MavenProject mavenProject : sortedProjects )
        {
            ProjectInstallerRequest pir =
                new ProjectInstallerRequest().setProject( mavenProject ).setCreateChecksum( true ).setUpdateReleaseInfo( true );

            installProject( exec.getSession().getProjectBuildingRequest(), pir );
        }

        try
        {
            tarArchiver.createArchive();
        }
        catch ( ArchiverException e )
        {
            LOGGER.error( e.getMessage(), e );
        }
        catch ( IOException e )
        {
            LOGGER.error( e.getMessage(), e );
        }

    }

//    private void deployProject( ProjectBuildingRequest projectBuildingRequest, ProjectDeployerRequest deployRequest,
//                                ArtifactRepository repository )
//    {
//
//        try
//        {
//            projectDeployer.deploy( projectBuildingRequest, deployRequest, repository );
//        }
//        catch ( IOException e )
//        {
//            LOGGER.error( "IOException", e );
//        }
//        catch ( NoFileAssignedException e )
//        {
//            LOGGER.error( "NoFileAssignedException", e );
//        }
//
//    }
//
    private void installProject( ProjectBuildingRequest pbr, ProjectInstallerRequest pir )
    {
        try
        {
            projectInstaller.install( pbr, pir );
        }
        catch ( IOException e )
        {
            LOGGER.error( "IOException", e );
        }
        catch ( ArtifactInstallerException e )
        {
            LOGGER.error( "ArtifactInstallerException", e );
        }
        catch ( NoFileAssignedException e )
        {
            LOGGER.error( "NoFileAssignedException", e );
        }
    }

    protected File getArchiveFile( File basedir, String finalName, String classifier, String archiveExt )
    {
        if ( basedir == null )
        {
            throw new IllegalArgumentException( "basedir is not allowed to be null" );
        }
        if ( finalName == null )
        {
            throw new IllegalArgumentException( "finalName is not allowed to be null" );
        }
        if ( archiveExt == null )
        {
            throw new IllegalArgumentException( "archiveExt is not allowed to be null" );
        }

        if ( finalName.isEmpty() )
        {
            throw new IllegalArgumentException( "finalName is not allowed to be empty." );
        }
        if ( archiveExt.isEmpty() )
        {
            throw new IllegalArgumentException( "archiveExt is not allowed to be empty." );
        }

        StringBuilder fileName = new StringBuilder( finalName );

        if ( StringUtils.isNotEmpty( classifier ) )
        {
            fileName.append( "-" ).append( classifier );
        }

        fileName.append( '.' );
        fileName.append( archiveExt );

        return new File( basedir, fileName.toString() );
    }

}
