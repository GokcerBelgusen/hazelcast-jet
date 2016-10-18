package com.hazelcast.jet2.impl.deployment.processors;

import com.hazelcast.jet2.Outbox;
import com.hazelcast.jet2.Processor;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import javax.annotation.Nonnull;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ApacheV2 implements Processor {

    public ApacheV2() {
    }

    @Override
    public void init(@Nonnull Outbox outbox) {

    }

    @Override
    public boolean process(int ordinal, Object item) {
        return true;
    }

    @Override
    public boolean complete() {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        URL resource = contextClassLoader.getResource("apachev2");
        BufferedReader reader = null;
        try {
            reader = Files.newBufferedReader(Paths.get(resource.toURI()));
            String firstLine = reader.readLine();
            String secondLine = reader.readLine();
            assertTrue(secondLine.contains("Apache"));
            assertNull(contextClassLoader.getResourceAsStream("apachev1"));
        } catch (IOException | URISyntaxException e) {
            fail();
        }
        return true;
    }
}
