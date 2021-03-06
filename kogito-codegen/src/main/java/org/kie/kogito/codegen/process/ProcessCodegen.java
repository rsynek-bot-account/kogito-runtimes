/*
 * Copyright 2019 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kie.kogito.codegen.process;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.drools.core.io.impl.FileSystemResource;
import org.drools.core.util.StringUtils;
import org.drools.core.xml.SemanticModules;
import org.jbpm.bpmn2.xml.BPMNDISemanticModule;
import org.jbpm.bpmn2.xml.BPMNExtensionsSemanticModule;
import org.jbpm.bpmn2.xml.BPMNSemanticModule;
import org.jbpm.compiler.canonical.ModelMetaData;
import org.jbpm.compiler.canonical.ProcessToExecModelGenerator;
import org.jbpm.compiler.canonical.UserTaskModelMetaData;
import org.jbpm.compiler.xml.XmlProcessReader;
import org.kie.api.definition.process.Process;
import org.kie.api.definition.process.WorkflowProcess;
import org.kie.api.io.Resource;
import org.kie.kogito.codegen.ConfigGenerator;
import org.kie.kogito.codegen.GeneratedFile;
import org.kie.kogito.codegen.Generator;
import org.kie.kogito.codegen.GeneratedFile.Type;
import org.kie.kogito.codegen.process.config.ProcessConfigGenerator;
import org.xml.sax.SAXException;

import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

public class ProcessCodegen implements Generator {

    private static final SemanticModules BPMN_SEMANTIC_MODULES = new SemanticModules();

    static {
        BPMN_SEMANTIC_MODULES.addSemanticModule(new BPMNSemanticModule());
        BPMN_SEMANTIC_MODULES.addSemanticModule(new BPMNExtensionsSemanticModule());
        BPMN_SEMANTIC_MODULES.addSemanticModule(new BPMNDISemanticModule());
    }

    public static ProcessCodegen ofPath(Path path) throws IOException {
        Path srcPath = Paths.get(path.toString(), "src");
        List<File> files = Files.walk(srcPath)
                .filter(p -> p.toString().endsWith(".bpmn") || p.toString().endsWith(".bpmn2"))
                .map(Path::toFile)
                .collect(Collectors.toList());
        return ofFiles(files);
    }

    public static ProcessCodegen ofFiles(Collection<File> processFiles) throws IOException {
        List<Process> allProcesses = parseProcesses(processFiles);
        return ofProcesses(allProcesses);
    }

    private static ProcessCodegen ofProcesses(List<Process> processes) {
        return new ProcessCodegen(processes);
    }

    private static List<Process> parseProcesses(Collection<File> processFiles) throws IOException {
        List<Process> processes = new ArrayList<>();
        for (File bpmnFile : processFiles) {
            FileSystemResource r = new FileSystemResource(bpmnFile);
            Collection<? extends Process> ps = parseProcessFile(r);
            processes.addAll(ps);
        }
        return processes;
    }

    private static Collection<? extends Process> parseProcessFile(Resource r) throws IOException {
        try {
            XmlProcessReader xmlReader = new XmlProcessReader(
                    BPMN_SEMANTIC_MODULES,
                    Thread.currentThread().getContextClassLoader());
            return xmlReader.read(r.getReader());
        } catch (SAXException e) {
            throw new ProcessParsingException("Could not parse file " + r.getSourcePath(), e);
        }
    }


    private String packageName;
    private String applicationCanonicalName;
    private String workItemHandlerConfigClass = null;
    private String processEventListenerConfigClass = null;
    private boolean dependencyInjection;
    private ModuleGenerator moduleGenerator;

    private final Map<String, WorkflowProcess> processes;
    private final Map<String, String> labels = new HashMap<>();
    private final List<GeneratedFile> generatedFiles = new ArrayList<>();

    public ProcessCodegen(
            Collection<? extends Process> processes) {
        this.processes = new HashMap<>();
        for (Process process : processes) {
            this.processes.put(process.getId(), (WorkflowProcess) process);
        }

    }

    public static String defaultWorkItemHandlerConfigClass(String packageName) {
        return packageName + ".WorkItemHandlerConfig";
    }

    public static String defaultProcessListenerConfigClass(String packageName) {
        return packageName + ".ProcessEventListenerConfig";
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
        this.moduleGenerator = new ModuleGenerator(packageName)
                .withCdi(dependencyInjection);
        this.applicationCanonicalName = packageName + ".Application";
    }

    public void setDependencyInjection(boolean di) {
        dependencyInjection = di;
        if (moduleGenerator != null) {
            moduleGenerator.withCdi(di);
        }
    }

    public ModuleGenerator moduleGenerator() {
        return moduleGenerator;
    }

    public ProcessCodegen withWorkItemHandlerConfig(String workItemHandlerConfigClass) {
        this.workItemHandlerConfigClass = workItemHandlerConfigClass;
        return this;
    }

    public ProcessCodegen withProcessEventListenerConfig(String customProcessListenerConfigExists) {
        this.processEventListenerConfigClass = customProcessListenerConfigExists;
        return this;
    }


    @Override
    public Collection<MethodDeclaration> factoryMethods() {
        return moduleGenerator.factoryMethods();
    }

    public List<GeneratedFile> generate() {
        if (processes.isEmpty()) {
            return Collections.emptyList();
        }

        List<ProcessGenerator> ps = new ArrayList<>();
        List<ProcessInstanceGenerator> pis = new ArrayList<>();
        List<ProcessExecutableModelGenerator> processExecutableModelGenerators = new ArrayList<>();
        List<ResourceGenerator> rgs = new ArrayList<>();

        List<String> publicProcesses = new ArrayList<>();

        Map<String, ModelMetaData> processIdToModel = new HashMap<>();
        Map<String, ModelClassGenerator> processIdToModelGenerator = new HashMap<>();
        
        Map<String, List<UserTaskModelMetaData>> processIdToUserTaskModel = new HashMap<>();

        // first we generate all the data classes from variable declarations
        for (WorkflowProcess workFlowProcess : processes.values()) {
            ModelClassGenerator mcg = new ModelClassGenerator(workFlowProcess);
            processIdToModelGenerator.put(workFlowProcess.getId(), mcg);
            processIdToModel.put(workFlowProcess.getId(), mcg.generate());
        }
        
        // then we generate user task inputs and outputs if any
        for (WorkflowProcess workFlowProcess : processes.values()) {
            UserTasksModelClassGenerator utcg = new UserTasksModelClassGenerator(workFlowProcess);
            processIdToUserTaskModel.put(workFlowProcess.getId(), utcg.generate());
        }

        // then we can instantiate the exec model generator
        // with the data classes that we have already resolved
        ProcessToExecModelGenerator execModelGenerator =
                new ProcessToExecModelGenerator(processIdToModel);

        // collect all process descriptors (exec model)
        for (WorkflowProcess workFlowProcess : processes.values()) {
            ProcessExecutableModelGenerator execModelGen =
                    new ProcessExecutableModelGenerator(workFlowProcess, execModelGenerator);
            execModelGen.generate();
            processExecutableModelGenerators.add(execModelGen);
        }
        
        

        // generate Process, ProcessInstance classes and the REST resource
        for (ProcessExecutableModelGenerator execModelGen : processExecutableModelGenerators) {
            String classPrefix = StringUtils.capitalize(execModelGen.extractedProcessId());
            WorkflowProcess workFlowProcess = execModelGen.process();
            ModelClassGenerator modelClassGenerator =
                    processIdToModelGenerator.get(execModelGen.getProcessId());

            ProcessGenerator p = new ProcessGenerator(
                    workFlowProcess,
                    execModelGen,
                    processes,
                    classPrefix,
                    modelClassGenerator.className(),
                    applicationCanonicalName)
                    .withCdi(dependencyInjection);

            ProcessInstanceGenerator pi = new ProcessInstanceGenerator(
                    workFlowProcess.getPackageName(),
                    classPrefix,
                    modelClassGenerator.generate());

            // do not generate REST endpoint if the process is not "public"
            if (execModelGen.isPublic()) {
                // create REST resource class for process
                ResourceGenerator resourceGenerator = new ResourceGenerator(
                        workFlowProcess,
                        modelClassGenerator.className(),
                        execModelGen.className())
                        .withCdi(dependencyInjection)
                        .withUserTasks(processIdToUserTaskModel.get(workFlowProcess.getId()));

                rgs.add(resourceGenerator);
            }

            moduleGenerator.addProcess(p);

            ps.add(p);
            pis.add(pi);
        }

        for (ModelClassGenerator modelClassGenerator : processIdToModelGenerator.values()) {
            ModelMetaData mmd = modelClassGenerator.generate();
            storeFile(Type.MODEL, modelClassGenerator.generatedFilePath(),
                      mmd.generate().getBytes());
        }
        
        for (List<UserTaskModelMetaData> utmd : processIdToUserTaskModel.values()) {
            
            for (UserTaskModelMetaData ut : utmd) {
                storeFile(Type.MODEL, UserTasksModelClassGenerator.generatedFilePath(ut.getInputModelClassName()), ut.generateInput().getBytes());
                
                storeFile(Type.MODEL, UserTasksModelClassGenerator.generatedFilePath(ut.getOutputModelClassName()), ut.generateOutput().getBytes());
            }
        }

        for (ResourceGenerator resourceGenerator : rgs) {
            storeFile(Type.REST, resourceGenerator.generatedFilePath(),
                      resourceGenerator.generate().getBytes());
        }

        for (ProcessGenerator p : ps) {
            storeFile(Type.PROCESS, p.generatedFilePath(), p.generate().getBytes());
        }

        for (ProcessInstanceGenerator pi : pis) {
            storeFile(Type.PROCESS_INSTANCE, pi.generatedFilePath(), pi.generate().getBytes());
        }

        if (workItemHandlerConfigClass != null) {
            moduleGenerator.setWorkItemHandlerClass(workItemHandlerConfigClass);
        }

        if (processEventListenerConfigClass != null) {
            moduleGenerator.setProcessEventListenerConfigClass(processEventListenerConfigClass);
        }

        for (ProcessExecutableModelGenerator legacyProcessGenerator : processExecutableModelGenerators) {
            if (legacyProcessGenerator.isPublic()) {
                publicProcesses.add(legacyProcessGenerator.extractedProcessId());
                labels.put(legacyProcessGenerator.label(), "process");// add the label id of the process with value set to process as resource type
            }
        }

        return generatedFiles;
    }

    @Override
    public void updateConfig(ConfigGenerator cfg) {
        // fixme: we need to pass on whether this is a drools-only project
        if (!processes.isEmpty()) {
            cfg.withProcessConfig(
                    new ProcessConfigGenerator()
                            .withWorkItemConfig(moduleGenerator.workItemConfigClass())
                            .withProcessEventListenerConfig(moduleGenerator.processEventListenerConfigClass()));
        }
    }

    private void storeFile(GeneratedFile.Type type, String path, byte[] data) {
        generatedFiles.add(new GeneratedFile(type, path, data));
    }

    public List<GeneratedFile> getGeneratedFiles() {
        return generatedFiles;
    }

    public Map<String, String> getLabels() {
        return labels;
    }

    @Override
    public Collection<BodyDeclaration<?>> applicationBodyDeclaration() {
        return moduleGenerator.getApplicationBodyDeclaration();
    }

}