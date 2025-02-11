/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.server.startup;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.neo4j.configuration.BootloaderSettings;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.io.fs.FileUtils;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.neo4j.configuration.SettingValueParsers.INT;
import static org.neo4j.server.startup.Bootloader.ENV_NEO4J_START_WAIT;

class ProcessManager
{
    private final BootloaderContext ctx;

    static class Behaviour
    {
        protected boolean inheritIO;
        protected boolean blocking;
        protected boolean redirectToUserLog;
        protected boolean storePid;
        protected boolean homeAndConfAsEnv;
        protected boolean shutdownHook;
        protected PrintStream outputConsumer;
        protected PrintStream errorConsumer;

        Behaviour inheritIO()
        {
            this.inheritIO = true;
            return this;
        }

        Behaviour blocking()
        {
            this.blocking = true;
            return this;
        }

        Behaviour withShutdownHook()
        {
            this.shutdownHook = true;
            return this;
        }

        Behaviour redirectToUserLog()
        {
            this.redirectToUserLog = true;
            return this;
        }

        Behaviour storePid()
        {
            this.storePid = true;
            return this;
        }

        Behaviour outputConsumer( PrintStream stream )
        {
            this.outputConsumer = stream;
            return this;
        }

        Behaviour errorConsumer( PrintStream stream )
        {
            this.errorConsumer = stream;
            return this;
        }

        Behaviour homeAndConfAsEnv()
        {
            this.homeAndConfAsEnv = true;
            return this;
        }
    }

    static Behaviour behaviour()
    {
        return new Behaviour();
    }

    ProcessManager( BootloaderContext ctx )
    {
        this.ctx = ctx;
    }

    long run( List<String> command, Behaviour behaviour ) throws BootFailureException
    {
        ProcessBuilder processBuilder = new ProcessBuilder( command );
        if ( behaviour.inheritIO )
        {
            processBuilder.inheritIO();
        }
        if ( behaviour.redirectToUserLog )
        {
            File userLog = ctx.config().get( GraphDatabaseSettings.store_user_log_path ).toFile();
            try
            {
                // Convenience for creating necessary directories and the user log file if it doesn't exist
                FileUtils.writeToFile( userLog.toPath(), "", true );
            }
            catch ( IOException e )
            {
                throw new BootFailureException( "Failure to create the user log file " + userLog + " due to " + e.getMessage(), 1 );
            }
            ProcessBuilder.Redirect redirect = ProcessBuilder.Redirect.appendTo( userLog );
            processBuilder.redirectOutput( redirect );
            processBuilder.redirectError( redirect );
        }

        if ( behaviour.homeAndConfAsEnv )
        {
            Map<String,String> env = processBuilder.environment();
            env.putIfAbsent( Bootloader.ENV_NEO4J_HOME, ctx.home().toString() );
            env.putIfAbsent( Bootloader.ENV_NEO4J_CONF, ctx.confDir().toString() );
        }

        Process process = null;
        try
        {
            if ( ctx.verbose )
            {
                ctx.out.println( "Executing command line: " + String.join( " ", command ) );
            }
            process = processBuilder.start();

            if ( behaviour.shutdownHook )
            {
                installShutdownHook( process );
            }
            if ( behaviour.storePid )
            {
                storePid( process.pid() );
            }
            if ( behaviour.blocking )
            {
                process.waitFor();
            }
            else
            {
                process.waitFor( ctx.getEnv( ENV_NEO4J_START_WAIT, 0, INT ), SECONDS );
            }

            if ( !process.isAlive() )
            {
                if ( !behaviour.inheritIO )
                {
                    PrintStream out = behaviour.outputConsumer != null ? behaviour.outputConsumer : ctx.out;
                    PrintStream err = behaviour.errorConsumer != null ? behaviour.errorConsumer : ctx.err;
                    out.write( process.getInputStream().readAllBytes() );
                    err.write( process.getErrorStream().readAllBytes() );
                }
                if ( process.exitValue() != 0 )
                {
                    throw new BootProcessFailureException( process.exitValue() );
                }
            }
            return process.pid();
        }
        catch ( BootFailureException e )
        {
            throw e; //rethrow
        }
        catch ( Exception e )
        {
            if ( process != null && process.isAlive() )
            {
                process.destroy();
            }
            throw new BootFailureException( "Unexpected error while starting. Aborting. " + e.getClass().getSimpleName() + " : " + e.getMessage(), e );
        }
    }

    private static void installShutdownHook( Process finalProcess )
    {
        Runtime.getRuntime().addShutdownHook( new Thread( () -> {
            if ( finalProcess.isAlive() )
            {
                finalProcess.destroy();
                while ( finalProcess.isAlive() )
                {
                    try
                    {
                        Thread.sleep( 10 );
                    }
                    catch ( InterruptedException e )
                    {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } ) );
    }

    Long getPidFromFile()
    {
        Path pidFilePath = pidFile();
        if ( Files.exists( pidFilePath ) )
        {
            try
            {
                try
                {
                    return Long.parseLong( Files.readString( pidFilePath ).trim() );
                }
                catch ( NumberFormatException e )
                {
                    deletePid();
                    return null;
                }
            }
            catch ( AccessDeniedException e )
            {
                throw new BootFailureException( "Access denied reading pid file " + pidFilePath, 1 );
            }
            catch ( IOException e )
            {
                throw new BootFailureException( "Unexpected error reading pid file " + pidFilePath, 1, e );
            }
        }
        return null;
    }

    ProcessHandle getProcessHandle( long pid ) throws BootFailureException
    {
        Optional<ProcessHandle> handleOption = ProcessHandle.of( pid );
        if ( handleOption.isEmpty() || !handleOption.get().isAlive() )
        {
            deletePid();
            return null;
        }
        return handleOption.get();
    }

    private void deletePid()
    {
        try
        {
            Files.deleteIfExists( pidFile() );
        }
        catch ( IOException e )
        {
            throw new BootFailureException( "Unable to delete pid file, " + e.getMessage(), 0 );
        }
    }

    private void storePid( long pid ) throws IOException
    {
        Path pidFilePath = pidFile();
        Files.createDirectories( pidFilePath.getParent() );
        Files.write( pidFilePath, Long.toString( pid ).getBytes(), CREATE, WRITE, TRUNCATE_EXISTING );
    }

    private Path pidFile()
    {
        return ctx.config().get( BootloaderSettings.pid_file );
    }
}
