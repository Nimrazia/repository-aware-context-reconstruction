package org.thesis.eval;

import com.github.javaparser.ast.body.MethodDeclaration;
import java.nio.file.Path;
import java.util.*;

final class Models {
  enum Kind { TYPE, METHOD, CONSTRUCTOR, FIELD, ANNOTATION, SUPERCLASS, INTERFACE }
  enum Provenance { REPOSITORY, JDK, EXTERNAL_MAVEN, EXTERNAL_GRADLE, EXTERNAL_OTHER, GENERATED, INHERITED_REPOSITORY, RUNTIME_SERVICE, UNRESOLVED }

  record ManifestRow(int repoIndex, String repoSlug, int methodNumber, int selectedId, int globalNumber,
                     String functionName, String sourceFile, String commit, int lineStart, int lineEnd,
                     double fanOut, String githubLink) {
    String className() { int i=functionName.lastIndexOf('.'); return i<0?"":functionName.substring(0,i); }
    String methodName() { int i=functionName.lastIndexOf('.'); return i<0?functionName:functionName.substring(i+1); }
    String key() { return repoIndex+":"+methodNumber; }
  }

  static final class Artifact {
    String llm, repoFolder, filename;
    Path path;
    ManifestRow mapped;
    String mappingStatus="UNMAPPED", mappingEvidence="";
    String targetPreservation="NOT_CHECKED";
    String normalizedGeneratedTarget="", normalizedRepositoryTarget="";
    boolean compilePass, executePass;
    String compileStatus="NOT_RUN", executionStatus="NOT_RUN", mainClass="";
    int compileExit=-1, executeExit=-1;
    long compileMs, executeMs;
    String compileOut="", compileErr="", executeOut="", executeErr="";
  }

  static final class Dep {
    final Kind kind;
    final String owner;
    final String name;
    final String signature;
    final Provenance provenance;
    final int depth;
    final String parent;
    final String path;
    final String source;
    final String resolution;
    Dep(Kind kind,String owner,String name,String signature,Provenance provenance,int depth,String parent,String path,String source,String resolution){
      this.kind=kind;this.owner=n(owner);this.name=n(name);this.signature=n(signature);this.provenance=provenance;
      this.depth=depth;this.parent=n(parent);this.path=n(path);this.source=n(source);this.resolution=n(resolution);
    }
    private static String n(String s){return s==null?"":s;}
    String exactKey(){return kind+"|"+owner+"|"+name+"|"+signature;}
    String looseKey(){return kind+"|"+simple(owner)+"|"+name+"|"+arity(signature);}
    String id(){return kind+":"+(owner.isBlank()?"":owner+".")+name+(signature.isBlank()?"":" "+signature);}
    static String simple(String s){if(s==null)return ""; s=s.replace('$','.'); int g=s.indexOf('<'); if(g>=0)s=s.substring(0,g); int i=s.lastIndexOf('.');return i<0?s:s.substring(i+1);}
    static int arity(String sig){if(sig==null||sig.isBlank())return -1; int a=sig.indexOf('('),b=sig.lastIndexOf(')'); if(a<0||b<a)return -1; String x=sig.substring(a+1,b).trim(); if(x.isEmpty())return 0; int depth=0,c=1; for(char ch:x.toCharArray()){if(ch=='<'||ch=='['||ch=='(')depth++; else if(ch=='>'||ch==']'||ch==')')depth--; else if(ch==','&&depth==0)c++;} return c;}
  }

  static final class Closure {
    ManifestRow method;
    String sourceStatus="";
    String rootOwner="", rootSignature="";
    final LinkedHashMap<String,Dep> deps=new LinkedHashMap<>();
    final List<String> diagnostics=new ArrayList<>();
    int maxDepth=0;
    void add(Dep d){String k=d.exactKey(); Dep old=deps.get(k); if(old==null||d.depth<old.depth){deps.put(k,d);maxDepth=Math.max(maxDepth,d.depth);}}
    Collection<Dep> all(){return deps.values();}
  }

  static final class MatchRow {
    Dep expected, actual;
    String result, evidence;
    boolean tp, provenanceCorrect;
  }

  static final class Score {
    Artifact artifact;
    Closure ground, generated;
    final List<MatchRow> matches=new ArrayList<>();
    int tp,fp,fn,tpJdk,fpJdk,fnJdk;
    double precision,recall,f1,precisionJdk,recallJdk,f1Jdk,provenanceAccuracy;
    final EnumMap<Kind,int[]> byKind=new EnumMap<>(Kind.class);
    final Map<Integer,int[]> byDepth=new TreeMap<>();
  }

  static final class LcomResult {
    ManifestRow method; String classFqn; int methodCount; int components; double lcom4;
    final List<String> notes=new ArrayList<>();
  }
}
