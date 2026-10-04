package com.pnomeer.lab.registry;

public record ModuleDescriptor(String id, String title, String collection) {
    public ModuleDescriptor {
        if (id == null || !id.matches("[a-z][a-z0-9.-]{0,99}")
                || collection == null || !collection.matches("[a-z][a-z0-9-]{0,49}")
                || title == null || title.isBlank() || title.length() > 200) {
            throw new IllegalArgumentException("Invalid module metadata");
        }
    }
}
