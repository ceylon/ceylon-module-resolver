package com.redhat.ceylon.cmr.api;

import java.util.Arrays;

public class ModuleQuery {
    protected String name;
    protected Type type;
    protected Retrieval retrieval;
    private Long start;
    private Long count;
    private long[] pagingInfo;
    private Integer binaryMajor;
    private Integer binaryMinor;
    private String memberName;
    private boolean memberSearchPackageOnly;
    private boolean memberSearchExact;

    public enum Type {
        SRC(ArtifactContext.SRC),
        CAR(ArtifactContext.CAR),
        JAR(ArtifactContext.JAR),
        JVM(ArtifactContext.CAR, ArtifactContext.JAR),
        JS(ArtifactContext.JS),
        DART(ArtifactContext.DART),
        CODE(ArtifactContext.CAR, ArtifactContext.JAR, ArtifactContext.JS),
        CEYLON_CODE(ArtifactContext.CAR, ArtifactContext.JS),
        ALL(ArtifactContext.allSuffixes());
        
        private String[] suffixes;

        Type(String... suffixes){
            this.suffixes = suffixes;
        }

        public String[] getSuffixes() {
            return suffixes;
        }
        
        public boolean includes(String... suffs) {
            return Arrays.asList(suffixes).containsAll(Arrays.asList(suffs));
        }
    }
    
    public enum Retrieval {
        ANY, ALL
    }
    
    public ModuleQuery(String name, Type type) {
        this(name, type, Retrieval.ANY);
    }
    
    public ModuleQuery(String name, Type type, Retrieval retrieval) {
        this.name = name;
        this.type = type;
        this.retrieval = retrieval;
    }
    
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public Retrieval getRetrieval() {
        return retrieval;
    }

    public void setRetrieval(Retrieval retrieval) {
        this.retrieval = retrieval;
    }

    public Long getStart() {
        return start;
    }

    public void setStart(Long start) {
        this.start = start;
    }

    public Long getCount() {
        return count;
    }

    public void setCount(Long count) {
        this.count = count;
    }

    public boolean isPaging() {
        return count != null || start != null;
    }

    public void setPagingInfo(long[] pagingInfo) {
        this.pagingInfo = pagingInfo;
    }

    public long[] getPagingInfo() {
        return pagingInfo;
    }

    public Integer getBinaryMajor() {
        return binaryMajor;
    }

    public void setBinaryMajor(Integer binaryMajor) {
        this.binaryMajor = binaryMajor;
    }

    public Integer getBinaryMinor() {
        return binaryMinor;
    }

    public void setBinaryMinor(Integer binaryMinor) {
        this.binaryMinor = binaryMinor;
    }

    public String getMemberName() {
        return memberName;
    }

    public void setMemberName(String memberName) {
        this.memberName = memberName;
    }

    public boolean isMemberSearchPackageOnly() {
        return memberSearchPackageOnly;
    }

    public void setMemberSearchPackageOnly(boolean memberSearchPackageOnly) {
        this.memberSearchPackageOnly = memberSearchPackageOnly;
    }

    public boolean isMemberSearchExact() {
        return memberSearchExact;
    }

    public void setMemberSearchExact(boolean memberSearchExact) {
        this.memberSearchExact = memberSearchExact;
    }

    @Override
    public String toString() {
        return "ModuleQuery[name=" + name + ",type=" + type + "]";
    }

}
