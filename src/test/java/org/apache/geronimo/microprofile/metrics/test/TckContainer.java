/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.geronimo.microprofile.metrics.test;

import static java.lang.ClassLoader.getSystemClassLoader;
import static java.lang.String.format;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.loader.WebappClassLoaderBase;
import org.apache.catalina.loader.WebappLoader;
import org.apache.meecrowave.Meecrowave;
import org.apache.meecrowave.arquillian.MeecrowaveContainer;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;

public class TckContainer extends MeecrowaveContainer {
    @Override
    public ProtocolMetaData deploy(final Archive<?> archive) {
        final File dump = toArchiveDump(archive);
        archive.as(ZipExporter.class).exportTo(dump, true);
        final String context = ""; // forced by tcks :(
        final Meecrowave container = getContainer();
        container.deployWebapp(new Meecrowave.DeploymentMeta(context, dump, c -> {
            c.setLoader(new WebappLoader() {
                @Override
                protected void startInternal() throws LifecycleException {
                    super.startInternal();
                    final WebappClassLoaderBase webappClassLoaderBase = WebappClassLoaderBase.class.cast(getClassLoader());
                    try {
                        final Method setJavaseClassLoader = WebappClassLoaderBase.class.getDeclaredMethod("setJavaseClassLoader", ClassLoader.class);
                        setJavaseClassLoader.setAccessible(true);
                        setJavaseClassLoader.invoke(webappClassLoaderBase, getSystemClassLoader());
                    } catch (final Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            });
        }));
        final Meecrowave.Builder configuration = container.getConfiguration();
        final int port = configuration.isSkipHttp() ? configuration.getHttpsPort() : configuration.getHttpPort();
        System.setProperty("test.url", format("http://localhost:%d", port)); // for tck
        return new ProtocolMetaData().addContext(new HTTPContext(configuration.getHost(), port).add(new Servlet("arquillian", context)));
    }

    private Meecrowave getContainer() {
        try {
            final Field field = getClass().getSuperclass().getDeclaredField("container");
            field.setAccessible(true);
            return Meecrowave.class.cast(field.get(this));
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private File toArchiveDump(final Archive<?> argValue) {
        try {
            final Method method = getClass().getSuperclass().getDeclaredMethod("toArchiveDump", Archive.class);
            method.setAccessible(true);
            return File.class.cast(method.invoke(this, argValue));
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
