package com.brucecli.llm;

import java.util.List;

public interface ModelSelectionService {
    List<ModelOption> modelOptions();

    ModelOption currentModel();

    ModelOption switchModel(String selector);

    String settingsPath();
}
