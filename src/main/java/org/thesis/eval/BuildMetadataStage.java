package org.thesis.eval;
import java.nio.file.*;import java.util.*;
final class BuildMetadataStage {
 static void write(Config cfg,Map<Integer,Path> repos,List<Models.ManifestRow> manifest)throws Exception{Map<Integer,Models.ManifestRow> one=new TreeMap<>();for(var m:manifest)one.putIfAbsent(m.repoIndex(),m);List<List<?>> rows=new ArrayList<>();for(var e:one.entrySet()){Path repo=repos.get(e.getKey());try(var s=Files.walk(repo,6)){for(Path p:s.filter(Files::isRegularFile).filter(p->Set.of("pom.xml","build.gradle","build.gradle.kts","settings.gradle","settings.gradle.kts","gradle.properties").contains(p.getFileName().toString())).toList())rows.add(List.of(e.getKey(),e.getValue().repoSlug(),repo.relativize(p).toString().replace('\\','/'),p.getFileName().toString()));}}CsvUtil.write(cfg.path("results").resolve("repository_ground_truth/build_metadata.csv"),List.of("repo","repository_slug","build_file","kind"),rows);}
}
