# Repository-Aware Context Reconstruction Using Large Language Models

## Overview

This project evaluates how effectively Large Language Models (LLMs) can reconstruct the repository context required to compile and execute an isolated Java method.

The evaluation uses **100 Java methods from different repositories in the CodeSearchNet dataset**. Dependency-rich methods were prioritised using fan-out to create a challenging benchmark containing methods with meaningful interactions with surrounding repository code.

The project combines **source-code analysis, recursive dependency resolution, automated compilation and execution, runtime coverage, dependency accuracy measurement, and structural analysis** to evaluate LLM-generated reconstructions.

---

## Main Workflow

```text
CodeSearchNet Java Methods
          ↓
Dependency-Rich Method Selection
          ↓
100 Target Java Methods
          ↓
Original Repository + Target Method
          ↓
LLM Context Reconstruction
          ↓
Reconstructed Java Context
          ↓
┌─────────────────────────────────┐
│      Automated Evaluation       │
│                                 │
│ • Compilation & Execution       │
│ • Recursive Dependency Analysis │
│ • JaCoCo Runtime Coverage       │
│ • Precision / Recall / F1       │
│ • LCOM4 + Spearman Analysis     │
└─────────────────────────────────┘
          ↓
Quantitative Evaluation Results
```

---

## Methodology

### 1. Method Selection

Candidate Java methods were analysed using **fan-out** as a measure of dependency richness. Higher-fan-out methods were prioritised to create a challenging repository-context reconstruction benchmark.

### 2. LLM Reconstruction

Each LLM receives the target method together with its available repository context and reconstructs the classes, methods, fields, types, and other components required to make the method usable.

### 3. Recursive Dependency Analysis

The original repository is analysed using **JavaParser and JavaSymbolSolver** to resolve the target method's dependencies recursively, including methods, constructors, fields, types, interfaces, annotations, and inheritance relationships.

### 4. Automated Validation

Reconstructed code is automatically **compiled and executed**. Target resolution and invocation are checked, with **JaCoCo** used to measure runtime instruction, branch, and line coverage.

### 5. Dependency Accuracy

The reconstructed context is compared against the repository-derived dependency ground truth using **Precision, Recall, F1, and provenance accuracy**.

### 6. Structural Analysis

**LCOM4** is calculated from the original declaring class and compared with reconstruction F1 using **Spearman correlation** to investigate the relationship between class cohesion and reconstruction accuracy.

---

## Research Questions

**RQ1 — Executability**  
Can an LLM reconstruct sufficient repository context to compile, execute, and exercise the target method?

**RQ2 — Dependency Reconstruction**  
How accurately does the reconstructed context recover the target method's actual repository dependency closure?

**RQ3 — Structural Cohesion**  
Is reconstruction accuracy associated with the structural cohesion of the target method's original class?

---

## Technology Stack

- **Java 21**
- **Python + javalang** — candidate and fan-out analysis
- **JavaParser + JavaSymbolSolver** — semantic dependency resolution
- **JaCoCo** — runtime coverage
- **Maven** — build and execution
- **Git / repository revisions** — reproducible source context
- **Precision, Recall, F1** — dependency evaluation
- **LCOM4 + Spearman correlation** — structural analysis

---

## Project Structure

```text
Repository-Aware-Context-Reconstruction/
├── README.md
├── pom.xml
├── reconstruction prompt.txt
├── config/
├── input/
├── results/
└── src/
```

### Evaluation Modules

| Component | Purpose |
|---|---|
| `FinalRQ1JacocoMain` | Compilation, execution and coverage |
| `FinalDependencyEvaluationMain` | Dependency reconstruction evaluation |
| `FinalRQ3SpearmanMain` | LCOM4 and correlation analysis |

---

## Build and Run

### Build

```bash
mvn clean package
```

### Compile

```bash
mvn clean compile
```

### Run Dependency Evaluation

```bash
mvn exec:java -Dexec.mainClass="org.thesis.eval.FinalDependencyEvaluationMain" -Dexec.args="."
```

Additional evaluation entry points are provided for RQ1 and RQ3.

---

## Results

The evaluation outputs are organised by research question:

```text
results/
├── RQ1/
│   └── RQ1_MODEL_SUMMARY.csv
├── RQ2/
│   └── FINAL_F1_BY_LLM.csv
└── RQ3/
    └── RQ3_SPEARMAN_WITH_PVALUES.csv
```

The structured results support comparison of **execution behaviour, runtime coverage, dependency reconstruction accuracy, and structural relationships** across the evaluated LLMs.

---

## Reproducibility

The project uses a consistent evaluation pipeline with:

- Fixed target-method dataset
- Repository-derived ground truth
- Versioned repository context
- Configurable dependency depth
- Automated compilation and execution
- Automated JaCoCo coverage collection
- Structured CSV result generation

---

This project brings together **repository mining, Java source analysis, recursive dependency reconstruction, LLM-based code generation, automated validation, runtime analysis, and quantitative evaluation** into a single reproducible workflow. It provides a practical way to examine whether an LLM can recover the surrounding context needed for an isolated method to function within an existing software system, while measuring both **whether the reconstructed code works** and **how accurately it represents the original repository dependencies**.
