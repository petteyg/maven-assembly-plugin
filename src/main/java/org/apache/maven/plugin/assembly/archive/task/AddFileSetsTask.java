package org.apache.maven.plugin.assembly.archive.task;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.io.input.ReaderInputStream;
import org.apache.maven.plugin.assembly.AssemblerConfigurationSource;
import org.apache.maven.plugin.assembly.archive.ArchiveCreationException;
import org.apache.maven.plugin.assembly.format.AssemblyFormattingException;
import org.apache.maven.plugin.assembly.format.FileFormatter;
import org.apache.maven.plugin.assembly.model.FileSet;
import org.apache.maven.plugin.assembly.utils.AssemblyFormatUtils;
import org.apache.maven.plugin.assembly.utils.LineEndings;
import org.apache.maven.plugin.assembly.utils.LineEndingsUtils;
import org.apache.maven.plugin.assembly.utils.TypeConversionUtils;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.components.io.functions.InputStreamTransformer;
import org.codehaus.plexus.components.io.resources.PlexusIoResource;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.logging.console.ConsoleLogger;

/**
 * @version $Id$
 */
public class AddFileSetsTask
    implements ArchiverTask
{

    private final List<FileSet> fileSets;

    private Logger logger;

    private MavenProject project;

    private MavenProject moduleProject;

    public AddFileSetsTask( final List<FileSet> fileSets )
    {
        this.fileSets = fileSets;
    }

    public AddFileSetsTask( final FileSet... fileSets )
    {
        this.fileSets = new ArrayList<FileSet>( Arrays.asList( fileSets ) );
    }

    public void execute( final Archiver archiver, final AssemblerConfigurationSource configSource )
        throws ArchiveCreationException, AssemblyFormattingException
    {
        // don't need this check here. it's more efficient here, but the logger is not actually
        // used until addFileSet(..)...and the check should be there in case someone extends the
        // class.
        // checkLogger();

        final File archiveBaseDir = configSource.getArchiveBaseDirectory();

        if ( archiveBaseDir != null )
        {
            if ( !archiveBaseDir.exists() )
            {
                throw new ArchiveCreationException( "The archive base directory '" + archiveBaseDir.getAbsolutePath()
                    + "' does not exist" );
            }
            else if ( !archiveBaseDir.isDirectory() )
            {
                throw new ArchiveCreationException( "The archive base directory '" + archiveBaseDir.getAbsolutePath()
                    + "' exists, but it is not a directory" );
            }
        }

        for ( final FileSet fileSet : fileSets )
        {
            addFileSet( fileSet, archiver, configSource, archiveBaseDir );
        }
    }

    protected void addFileSet( final FileSet fileSet, final Archiver archiver,
                               final AssemblerConfigurationSource configSource, final File archiveBaseDir )
        throws AssemblyFormattingException, ArchiveCreationException
    {
        // throw this check in just in case someone extends this class...
        checkLogger();

        if ( project == null )
        {
            project = configSource.getProject();
        }

        final File basedir = project.getBasedir();

        String destDirectory = fileSet.getOutputDirectory();

        if ( destDirectory == null )
        {
            destDirectory = fileSet.getDirectory();
        }

        destDirectory =
            AssemblyFormatUtils.getOutputDirectory( destDirectory, configSource.getProject(), moduleProject, project,
                                                    configSource.getFinalName(), configSource );

        if ( logger.isDebugEnabled() )
        {
            logger.debug( "FileSet[" + destDirectory + "]" + " dir perms: "
                + Integer.toString( archiver.getOverrideDirectoryMode(), 8 ) + " file perms: "
                + Integer.toString( archiver.getOverrideFileMode(), 8 )
                + ( fileSet.getLineEnding() == null ? "" : " lineEndings: " + fileSet.getLineEnding() ) );
        }

        logger.debug( "The archive base directory is '" + archiveBaseDir + "'" );

        File fileSetDir = getFileSetDirectory( fileSet, basedir, archiveBaseDir );

        if ( fileSetDir.exists() )
        {
            InputStreamTransformer fileSetTransformers = getFileSetTransformers( configSource, fileSetDir, fileSet );

            if ( fileSetDir.getPath().equals( File.separator ) )
            {
                throw new AssemblyFormattingException( "Your assembly descriptor specifies a directory of "
                    + File.separator
                    + ", which is your *entire* file system.\nThese are not the files you are looking for" );
            }
            final AddDirectoryTask task = new AddDirectoryTask( fileSetDir, fileSetTransformers );

            final int dirMode = TypeConversionUtils.modeToInt( fileSet.getDirectoryMode(), logger );
            if ( dirMode != -1 )
            {
                task.setDirectoryMode( dirMode );
            }

            final int fileMode = TypeConversionUtils.modeToInt( fileSet.getFileMode(), logger );
            if ( fileMode != -1 )
            {
                task.setFileMode( fileMode );
            }

            task.setUseDefaultExcludes( fileSet.isUseDefaultExcludes() );

            final List<String> excludes = fileSet.getExcludes();
            excludes.add( "**/*.filtered" );
            excludes.add( "**/*.formatted" );
            task.setExcludes( excludes );

            task.setIncludes( fileSet.getIncludes() );
            task.setOutputDirectory( destDirectory );

            task.execute( archiver, configSource );
        }
    }

    private InputStreamTransformer getFileSetTransformers( final AssemblerConfigurationSource configSource, File fileSetDir,
                                                            final org.apache.maven.plugin.assembly.model.FileSet set )
        throws AssemblyFormattingException
    {
        final String lineEndingHint = set.getLineEnding();

        String lineEnding = LineEndingsUtils.getLineEndingCharacters( lineEndingHint );

        if ( ( lineEnding != null ) || set.isFiltered() )
        {
            InputStreamTransformer isf = new InputStreamTransformer()
            {
                public InputStream transform( PlexusIoResource plexusIoResource, InputStream inputStream )
                    throws IOException
                {
                    FileFormatter fileFormatter = new FileFormatter( configSource, logger );

                    if ( set.isFiltered() )
                    {
                        final String encoding = configSource.getEncoding();

                        Reader source = encoding != null ? new InputStreamReader( inputStream, encoding )
                            : new InputStreamReader( inputStream ); // wtf platform encoding ?
                        try
                        {
                            Reader filtered = fileFormatter.doReaderFilter( source, plexusIoResource.getName(),
                                                                            configSource.getEncoding(),
                                                                            configSource.getEscapeString(),
                                                                            configSource.getDelimiters() );
                            final ReaderInputStream readerInputStream = encoding != null ? new ReaderInputStream( filtered, encoding)
                                : new ReaderInputStream( filtered );

                            LineEndings lineEnding = LineEndingsUtils.getLineEnding( lineEndingHint );
                            if ( !LineEndings.keep.equals( lineEnding ) )
                            {
                                return LineEndingsUtils.lineEndingConverter( readerInputStream, lineEnding );

                            }
                            return readerInputStream;

                        }
                        catch ( AssemblyFormattingException e )
                        {
                            throw new IOException( e );
                        }

                    }
                    else
                    {
                        return inputStream;
                    }
                }
            };
            return isf;
        }
        else
        {
            logger.debug( "NOT reformatting any files in " + fileSetDir );
            return null;
        }
    }


    protected File getFileSetDirectory( final FileSet fileSet, final File basedir, final File archiveBaseDir )
        throws ArchiveCreationException, AssemblyFormattingException
    {
        String sourceDirectory = fileSet.getDirectory();

        if ( sourceDirectory == null || sourceDirectory.trim().length() < 1 )
        {
            sourceDirectory = basedir.getAbsolutePath();
        }

        File fileSetDir;

        if ( archiveBaseDir == null )
        {
            fileSetDir = new File( sourceDirectory );

            if ( !fileSetDir.isAbsolute() )
            {
                fileSetDir = new File( basedir, sourceDirectory );
            }
        }
        else
        {
            fileSetDir = new File( archiveBaseDir, sourceDirectory );
        }

        return fileSetDir;
    }

    private void checkLogger()
    {
        if ( logger == null )
        {
            logger = new ConsoleLogger( Logger.LEVEL_INFO, "AddFileSetsTask-internal" );
        }
    }

    public void setLogger( final Logger logger )
    {
        this.logger = logger;
    }

    public void setProject( final MavenProject project )
    {
        this.project = project;
    }

    public MavenProject getModuleProject()
    {
        return moduleProject;
    }

    public void setModuleProject( final MavenProject moduleProject )
    {
        this.moduleProject = moduleProject;
    }

}
