package com.brucecli.llm;

public record ModelOption(String provider, String model) {
    public ModelOption {
        provider = provider == null ? "" : provider.trim();
        model = model == null ? "" : model.trim();
    }

    public String selector() {
        return provider + "/" + model;
    }

    public String display() {
        return model + " [" + provider + "]";
    }

    public boolean matches(ModelOption other) {
        return other != null
            && provider.equalsIgnoreCase(other.provider())
            && model.equalsIgnoreCase(other.model());
    }
}
