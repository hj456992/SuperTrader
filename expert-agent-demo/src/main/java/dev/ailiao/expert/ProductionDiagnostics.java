package dev.ailiao.expert;

/** HTTP与worker复用的安全定位信息，不输出SQL、参数或异常正文。 */
final class ProductionDiagnostics {
    static void log(String operation,Exception error){
        StringBuilder diagnostic=new StringBuilder("time=").append(java.time.Instant.now()).append(" Production ").append(operation).append(": ").append(error.getClass().getSimpleName());
        if(error instanceof java.sql.SQLException sql)diagnostic.append(" SQLState=").append(sql.getSQLState()).append(" vendorCode=").append(sql.getErrorCode());
        if(error instanceof org.postgresql.util.PSQLException pg&&pg.getServerErrorMessage()!=null)diagnostic.append(" routine=").append(pg.getServerErrorMessage().getRoutine()).append(" constraint=").append(pg.getServerErrorMessage().getConstraint());
        java.util.Set<Throwable> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Throwable cause=error;int depth=0;
        while(cause!=null&&depth<4&&seen.add(cause)){
            if(depth>0)diagnostic.append("\n  cause[").append(depth).append("]=").append(cause.getClass().getName());
            int frames=0;
            for(StackTraceElement frame:cause.getStackTrace()){
                String owner=frame.getClassName();if(!owner.startsWith("dev.ailiao.expert.")&&!owner.startsWith("org.postgresql."))continue;
                if(frames++>=12){diagnostic.append("\n  stackTruncated=true");break;}
                diagnostic.append("\n  at ").append(owner).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber());
            }
            cause=cause.getCause();depth++;
        }
        if(cause!=null)diagnostic.append("\n  causeChainTruncated=true");
        System.err.println(diagnostic);
    }
}
