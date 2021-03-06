/*
 * Copyright 2005 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.modelcompiler.builder;

import java.util.ArrayList;
import java.util.List;

import org.drools.compiler.compiler.io.memory.MemoryFileSystem;
import org.drools.compiler.kie.builder.impl.InternalKieModule;
import org.drools.compiler.kie.builder.impl.ResultsImpl;

public class CanonicalModelCodeGenerationKieProject extends CanonicalModelKieProject {

    private boolean hasCdi = ModelWriter.HAS_CDI;
    private boolean oneClassPerRule = false;

    public CanonicalModelCodeGenerationKieProject(InternalKieModule kieModule, ClassLoader classLoader) {
        super(true, kieModule, classLoader);
    }

    public CanonicalModelCodeGenerationKieProject withCdi(boolean hasCdi) {
        this.hasCdi = hasCdi;
        return this;
    }

    protected boolean hasCdi() {
        return hasCdi;
    }

    public CanonicalModelCodeGenerationKieProject withOneClassPerRule(boolean oneClassPerRule) {
        this.oneClassPerRule = oneClassPerRule;
        return this;
    }

    @Override
    public void writeProjectOutput( MemoryFileSystem trgMfs, ResultsImpl messages ) {
        MemoryFileSystem srcMfs = new MemoryFileSystem();
        List<String> generatedSourceFiles = new ArrayList<>();
        ModelWriter modelWriter = new ModelWriter();
        for (ModelBuilderImpl modelBuilder : modelBuilders) {
            ModelWriter.Result result = modelWriter.writeModel(srcMfs, modelBuilder.getPackageModels(), oneClassPerRule);
            generatedSourceFiles.addAll(result.getModelFiles());
        }

        KieModuleModelMethod modelMethod = new KieModuleModelMethod(kBaseModels);
        new org.drools.modelcompiler.builder.ModelSourceClass(
                getInternalKieModule().getReleaseId(), modelMethod, generatedSourceFiles)
                .write(srcMfs);
        new ProjectSourceClass(modelMethod).withCdi(hasCdi).write(srcMfs);

        srcMfs.copyFolder(srcMfs.getFolder("src/main/java"), trgMfs, trgMfs.getFolder("."));
        writeModelFile(generatedSourceFiles, trgMfs);
    }
}