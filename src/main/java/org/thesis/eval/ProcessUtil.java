package org.thesis.eval;
import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;
final class ProcessUtil {
 record R(int exit,boolean timeout,long ms,String out,String err){}
 static R run(List<String> cmd,Path cwd,int timeoutSeconds)throws Exception{long t=System.nanoTime();ProcessBuilder pb=new ProcessBuilder(cmd);pb.directory(cwd.toFile());Process p=pb.start();ExecutorService ex=Executors.newFixedThreadPool(2);Future<String> o=ex.submit(()->read(p.getInputStream()));Future<String> e=ex.submit(()->read(p.getErrorStream()));boolean ok=p.waitFor(timeoutSeconds,TimeUnit.SECONDS);if(!ok){p.destroyForcibly();p.waitFor(5,TimeUnit.SECONDS);}String out=get(o),err=get(e);ex.shutdownNow();return new R(ok?p.exitValue():-999,!ok,(System.nanoTime()-t)/1_000_000,out,err);}
 static String read(InputStream in)throws IOException{return new String(in.readAllBytes(),StandardCharsets.UTF_8);}static String get(Future<String>f){try{return f.get(3,TimeUnit.SECONDS);}catch(Exception e){return "";}}
 static String findExecutable(String name){String ext=System.getProperty("os.name","").toLowerCase().contains("win")?".exe":"";String path=System.getenv("PATH");if(path!=null)for(String d:path.split(java.io.File.pathSeparator)){Path p=Path.of(d,name+ext);if(Files.isRegularFile(p))return p.toString();}return name;}
}
