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

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.stream.Stream;

import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.CDI;

import org.apache.catalina.Context;
import org.apache.meecrowave.Meecrowave;
import org.apache.meecrowave.arquillian.MeecrowaveContainer;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.context.annotation.ContainerScoped;
import org.jboss.arquillian.container.spi.context.annotation.DeploymentScoped;
import org.jboss.arquillian.container.spi.event.container.AfterDeploy;
import org.jboss.arquillian.container.spi.event.container.AfterStart;
import org.jboss.arquillian.container.spi.event.container.BeforeUnDeploy;
import org.jboss.arquillian.container.test.impl.client.protocol.local.LocalProtocol;
import org.jboss.arquillian.container.test.spi.client.deployment.ApplicationArchiveProcessor;
import org.jboss.arquillian.container.test.spi.client.deployment.DeploymentPackager;
import org.jboss.arquillian.container.test.spi.client.protocol.Protocol;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.InstanceProducer;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.core.api.annotation.Observes;
import org.jboss.arquillian.core.spi.LoadableExtension;
import org.jboss.arquillian.test.spi.TestClass;
import org.jboss.arquillian.test.spi.TestEnricher;
import org.jboss.arquillian.test.spi.event.suite.Before;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;

public class ArquillianSetup implements LoadableExtension {
    @Override
    public void register(final ExtensionBuilder extensionBuilder) {
        extensionBuilder.observer(EnvSetup.class)
                .override(DeployableContainer.class, MeecrowaveContainer.class, TckContainer.class)
                .override(Protocol.class, LocalProtocol.class, ForceWarProtocol.class)
                .service(TestEnricher.class, ParamEnricher.class)
                .service(ApplicationArchiveProcessor.class, EnsureTestIsInTheArchiveProcessor.class);
    }

    public static class EnvSetup {
        @Inject
        @DeploymentScoped
        private InstanceProducer<BeanManager> beanManagerInstanceProducer;

        @Inject
        @DeploymentScoped
        private InstanceProducer<ClassLoader> appClassLoaderInstanceProducer;

        @Inject
        @ContainerScoped
        private InstanceProducer<Meecrowave> container;

        public void onDeploy(@Observes final AfterStart afterStart) throws Exception {
            final DeployableContainer<?> deployableContainer = afterStart.getDeployableContainer();
            final Field container = MeecrowaveContainer.class.getDeclaredField("container");
            container.setAccessible(true);
            final Meecrowave meecrowave = Meecrowave.class.cast(container.get(deployableContainer));
            this.container.set(meecrowave);
        }

        public void onDeploy(@Observes final AfterDeploy afterDeploy) {
            final Meecrowave meecrowave = container.get();
            final ClassLoader appLoader = Context.class.cast(meecrowave.getTomcat().getHost().findChildren()[0]).getLoader().getClassLoader();
            appClassLoaderInstanceProducer.set(appLoader);

            final Thread thread = Thread.currentThread();
            thread.setContextClassLoader(appLoader);
            beanManagerInstanceProducer.set(CDI.current().getBeanManager());
        }

        public void onUndeploy(@Observes final BeforeUnDeploy beforeUnDeploy) {
            final ClassLoader cl = container.get().getTomcat().getServer().getParentClassLoader();
            Thread.currentThread().setContextClassLoader(cl);
        }

        public void enrich(@Observes final Before before) throws Exception {
            final Thread thread = Thread.currentThread();
            final ClassLoader classLoader = thread.getContextClassLoader();
            thread.setContextClassLoader(appClassLoaderInstanceProducer.get());
            try {
                container.get().inject(before.getTestInstance()).close();
            } finally {
                thread.setContextClassLoader(classLoader);
            }
        }
    }

    public static class ParamEnricher implements TestEnricher {
        @Inject
        @DeploymentScoped
        private Instance<BeanManager> beanManagerInstanceProducer;

        @Inject
        @DeploymentScoped
        private Instance<ClassLoader> appClassLoaderInstanceProducer;

        @Override
        public void enrich(final Object testCase) {
            // no-op
        }

        @Override
        public Object[] resolve(final Method method) {
            return Stream.of(method.getParameters())
                    .map(p -> {
                        final Thread thread = Thread.currentThread();
                        final ClassLoader classLoader = thread.getContextClassLoader();
                        thread.setContextClassLoader(appClassLoaderInstanceProducer.get());
                        try {
                            final CDI<Object> cdi = CDI.current();
                            final Annotation[] qualifiers = Stream.of(p.getAnnotations()).filter(it -> cdi.getBeanManager().isQualifier(it.annotationType())).toArray(Annotation[]::new);
                            return cdi.select(p.getType(), qualifiers).get();
                        } catch (final RuntimeException re) {
                            return null;
                        } finally {
                            thread.setContextClassLoader(classLoader);
                        }
                    })
                    .toArray();
        }
    }

    public static class ForceWarProtocol extends LocalProtocol {
        @Override
        public DeploymentPackager getPackager() {
            return (deployment, collection) -> {
                final Archive<?> archive = deployment.getApplicationArchive();
                if (JavaArchive.class.isInstance(archive)) {
                    return ShrinkWrap.create(WebArchive.class, archive.getName().replace(".jar", ".war"))
                            .addAsLibraries(archive);
                }
                return archive;
            };
        }
    }

    public static class EnsureTestIsInTheArchiveProcessor implements ApplicationArchiveProcessor {
        @Override
        public void process(final Archive<?> archive, final TestClass testClass) {
            if (JavaArchive.class.isInstance(archive) && !archive.contains(testClass.getName().replace('.', '/') + ".class")) {
                JavaArchive.class.cast(archive).addClass(testClass.getJavaClass());
            }
        }
    }
}
