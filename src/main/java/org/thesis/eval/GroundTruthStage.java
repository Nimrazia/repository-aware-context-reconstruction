package org.thesis.eval;
import java.nio.file.*;import java.util.*;
final class GroundTruthStage {
 static Map<String,Models.Closure> run(Config cfg,List<Models.ManifestRow> manifest,Map<Integer,SourceIndex> indexes)throws Exception{Map<String,Models.Closure> out=new LinkedHashMap<>();int i=0;for(var m:manifest){i++;System.out.printf("[GROUND TRUTH %d/%d] repo%d method_%d %s%n",i,manifest.size(),m.repoIndex(),m.methodNumber(),m.functionName());SourceIndex idx=indexes.get(m.repoIndex());Models.Closure c=new DependencyExtractor(idx,cfg,m).extract();c.diagnostics.addAll(idx.diagnostics);out.put(m.key(),c);}return out;}
}
