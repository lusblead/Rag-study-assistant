package com.rag.backend.paper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.common.BizException;
import com.rag.backend.course.CourseMapper;
import com.rag.backend.paper.model.*;
import com.rag.backend.question.QuestionBatchGenerationService;
import com.rag.backend.question.QuestionMapper;
import com.rag.backend.question.model.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.*;

@Service
public class PaperService {
    public static final String DEFAULT_TEMPLATE = "chinese_high_school_standard_v1";
    private final PaperMapper paperMapper; private final PaperQuestionMapper itemMapper;
    private final QuestionMapper questionMapper; private final CourseMapper courseMapper;
    private final QuestionBatchGenerationService generationService; private final ObjectMapper objectMapper;

    public PaperService(PaperMapper paperMapper, PaperQuestionMapper itemMapper, QuestionMapper questionMapper,
                        CourseMapper courseMapper, QuestionBatchGenerationService generationService, ObjectMapper objectMapper) {
        this.paperMapper=paperMapper; this.itemMapper=itemMapper; this.questionMapper=questionMapper;
        this.courseMapper=courseMapper; this.generationService=generationService; this.objectMapper=objectMapper;
    }

    public PaperDetail generate(PaperRequests.Generate request) {
        if(request==null || request.courseId()==null) throw new BizException(400,"courseId 不能为空");
        if(courseMapper.selectById(request.courseId())==null) throw new BizException(404,"课程不存在: "+request.courseId());
        String subject=StringUtils.hasText(request.subject())?request.subject().trim():Question.SUBJECT_CHINESE;
        if(!Question.SUBJECT_CHINESE.equals(subject)) throw new BizException(400,"当前完整套卷模板仅支持 chinese 学科");
        String template=StringUtils.hasText(request.templateCode())?request.templateCode().trim():DEFAULT_TEMPLATE;
        if(!Set.of(DEFAULT_TEMPLATE,"chinese_middle_school_standard_v1").contains(template))
            throw new BizException(400,"不支持的套卷模板: "+template);

        Paper paper=new Paper(); paper.setCourseId(request.courseId()); paper.setSubject(subject);
        paper.setTitle(StringUtils.hasText(request.title())?request.title().trim():defaultTitle(request.gradeLevel()));
        paper.setPaperType(value(request.paperType(),"practice")); paper.setGradeLevel(value(request.gradeLevel(),"senior_high"));
        paper.setDifficulty(value(request.difficulty(),Question.DIFF_MEDIUM));
        paper.setDurationMinutes(request.durationMinutes()==null?120:clamp(request.durationMinutes(),30,300));
        paper.setTotalScore(request.totalScore()==null?new BigDecimal("100"):request.totalScore());
        paper.setTemplateCode(template); paper.setRequirements(request.requirements()); paper.setWarnings("[]");
        paperMapper.insert(paper);

        List<String> warnings=new ArrayList<>(); int order=1;
        for(TemplateSection section:sections(template)) {
            try {
                QuestionGenerationRequest generation=new QuestionGenerationRequest();
                generation.setCourseId(request.courseId()); generation.setDocumentIds(request.documentIds());
                generation.setSubject(Question.SUBJECT_CHINESE); generation.setCount(section.count());
                generation.setType(section.type()); generation.setQuestionTypes(List.of(section.type()));
                generation.setDifficulty(paper.getDifficulty()); generation.setMode("exam");
                generation.setTitle(paper.getTitle()+" - "+section.title());
                generation.setRequirement(section.prompt()+"\n"+value(request.requirements(),"严格依据课程资料命题。"));
                QuestionBatchDetail batch=generateWithRetry(generation,section);
                BigDecimal each=section.score().divide(BigDecimal.valueOf(batch.getQuestions().size()),2,java.math.RoundingMode.HALF_UP);
                for(Question question:batch.getQuestions()) {
                    PaperQuestion item=new PaperQuestion(); item.setPaperId(paper.getId()); item.setQuestionId(question.getId());
                    item.setSectionKey(section.key()); item.setSectionTitle(section.title()); item.setSectionInstructions(section.instructions());
                    item.setQuestionOrder(order++); item.setScore(each); itemMapper.insert(item);
                }
                if(batch.getQuestions().size()<section.count()) warnings.add(section.title()+"仅生成 "+batch.getQuestions().size()+" 道题");
            } catch(RuntimeException ex) {
                warnings.add(section.title()+"生成失败："+safeMessage(ex));
            }
        }
        if(order==1) { paperMapper.deleteById(paper.getId()); throw new BizException(500,"所有试卷分区均生成失败，请检查模型配置和课程资料"); }
        paper.setWarnings(writeWarnings(warnings)); paperMapper.update(paper);
        return detail(paper.getId());
    }

    public List<Paper> list(Long courseId,String subject){
        if(courseId==null) throw new BizException(400,"courseId 不能为空");
        return paperMapper.selectList(courseId,subject);
    }
    public PaperDetail detail(Long id){
        Paper paper=requirePaper(id); List<PaperQuestion> items=itemMapper.selectByPaperId(id);
        for(PaperQuestion item:items) item.setQuestion(questionMapper.selectById(item.getQuestionId()));
        Map<String,List<PaperQuestion>> grouped=new LinkedHashMap<>();
        items.forEach(item->grouped.computeIfAbsent(item.getSectionKey(),ignored->new ArrayList<>()).add(item));
        List<PaperDetail.Section> sections=new ArrayList<>();
        for(List<PaperQuestion> group:grouped.values()) {
            PaperQuestion first=group.get(0); BigDecimal score=group.stream().map(PaperQuestion::getScore).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);
            sections.add(new PaperDetail.Section(first.getSectionKey(),first.getSectionTitle(),first.getSectionInstructions(),score,group));
        }
        return new PaperDetail(paper,sections,readWarnings(paper.getWarnings()));
    }

    @Transactional public PaperDetail update(Long id,PaperRequests.Update request){
        Paper paper=requirePaper(id);
        if(request!=null){ if(StringUtils.hasText(request.title()))paper.setTitle(request.title().trim());
            if(StringUtils.hasText(request.paperType()))paper.setPaperType(request.paperType().trim());
            if(StringUtils.hasText(request.difficulty()))paper.setDifficulty(request.difficulty().trim());
            if(request.durationMinutes()!=null)paper.setDurationMinutes(clamp(request.durationMinutes(),30,300));
            if(request.totalScore()!=null)paper.setTotalScore(request.totalScore()); }
        paperMapper.update(paper); return detail(id);
    }
    @Transactional public void delete(Long id){requirePaper(id);itemMapper.deleteByPaperId(id);paperMapper.deleteById(id);}
    @Transactional public PaperDetail addQuestion(Long id,PaperRequests.AddQuestion request){
        requirePaper(id); if(request==null||request.questionId()==null||questionMapper.selectById(request.questionId())==null) throw new BizException(404,"题目不存在");
        PaperQuestion item=new PaperQuestion(); item.setPaperId(id);item.setQuestionId(request.questionId());
        item.setSectionKey(value(request.sectionKey(),"custom"));item.setSectionTitle(value(request.sectionTitle(),"自定义题目"));
        item.setSectionInstructions(request.sectionInstructions());item.setQuestionOrder(itemMapper.maxOrder(id)+1);item.setScore(request.score());
        try{itemMapper.insert(item);}catch(RuntimeException ex){throw new BizException(400,"题目已在试卷中");} return detail(id);
    }
    @Transactional public PaperDetail reorder(Long id,PaperRequests.Reorder request){
        requirePaper(id); if(request==null||request.items()==null||request.items().isEmpty())throw new BizException(400,"items 不能为空");
        Set<Integer> orders=new HashSet<>();
        for(PaperRequests.ReorderItem source:request.items()){
            if(source.questionId()==null||source.questionOrder()==null||source.questionOrder()<1||!orders.add(source.questionOrder()))throw new BizException(400,"题目顺序必须为不重复的正整数");
            PaperQuestion item=new PaperQuestion();item.setPaperId(id);item.setQuestionId(source.questionId());item.setSectionKey(value(source.sectionKey(),"custom"));item.setSectionTitle(value(source.sectionTitle(),"自定义题目"));item.setSectionInstructions(source.sectionInstructions());item.setQuestionOrder(source.questionOrder());item.setScore(source.score());
            if(itemMapper.update(item)==0)throw new BizException(404,"试卷中不存在题目: "+source.questionId());
        } return detail(id);
    }
    @Transactional public void removeQuestion(Long id,Long questionId){requirePaper(id);if(itemMapper.deleteOne(id,questionId)==0)throw new BizException(404,"试卷中不存在该题目");}

    private Paper requirePaper(Long id){Paper paper=paperMapper.selectById(id);if(paper==null)throw new BizException(404,"试卷不存在: "+id);return paper;}
    private String writeWarnings(List<String> warnings){try{return objectMapper.writeValueAsString(warnings);}catch(Exception e){return "[]";}}
    private List<String> readWarnings(String raw){try{return objectMapper.readValue(value(raw,"[]"),new TypeReference<>(){});}catch(Exception e){return List.of();}}
    private String safeMessage(RuntimeException ex){return StringUtils.hasText(ex.getMessage())?ex.getMessage():ex.getClass().getSimpleName();}
    private String value(String text,String fallback){return StringUtils.hasText(text)?text.trim():fallback;}
    private int clamp(int value,int min,int max){return Math.max(min,Math.min(max,value));}
    private String defaultTitle(String grade){return (StringUtils.hasText(grade)?grade:"高中")+"语文综合测试卷";}
    private QuestionBatchDetail generateWithRetry(QuestionGenerationRequest request, TemplateSection section) {
        RuntimeException firstFailure;
        try { return generationService.generate(request); }
        catch (RuntimeException ex) { firstFailure=ex; }
        request.setRequirement(section.prompt()+"\n首次生成未通过结构校验（"+safeMessage(firstFailure)+"）。请重新生成，严格使用指定英文键名，不要把 material、subQuestions 或 requirements 改名，也不要输出 Markdown。\n"+value(request.getRequirement(),""));
        try { return generationService.generate(request); }
        catch (RuntimeException retryFailure) { throw new BizException(500,"重试后仍失败："+safeMessage(retryFailure)); }
    }
    private List<TemplateSection> sections(String template){return List.of(
        new TemplateSection("language_basic","一、语言文字运用","完成下列语言文字运用题。","language_basic",4,new BigDecimal("15"),"生成4道彼此独立的语言基础题，覆盖字词、成语、病句或表达运用。"),
        new TemplateSection("modern_reading","二、现代文阅读","阅读下面的文字，完成各题。","modern_reading",1,new BigDecimal("20"),"生成1组现代文复合阅读题，questionData必须有material和至少4个subQuestions。"),
        new TemplateSection("classical_chinese","三、文言文阅读","阅读下面的文言文，完成各题。","classical_chinese_reading",1,new BigDecimal("20"),"生成1组文言文复合阅读题，材料必须来自资料，至少4个小题并包含翻译或解释。"),
        new TemplateSection("poetry","四、古诗词鉴赏","阅读下面的诗歌，完成各题。","poetry_appreciation",1,new BigDecimal("10"),"生成1组古诗词鉴赏复合题，保留诗歌换行，至少2个主观小题。"),
        new TemplateSection("memorization","五、名篇名句默写","补写出下列句子中的空缺部分。","fill_blank",3,new BigDecimal("5"),"生成3道名篇名句默写题，只能使用资料中明确存在的原文。"),
        new TemplateSection("composition","六、写作","阅读下面的材料，根据要求写作。","composition",1,new BigDecimal("30"),"生成1道材料作文，questionData.requirements包含genre、wordCount和mustInclude，answerSchema包含rubric和scoringPoints。")
    );}
    private record TemplateSection(String key,String title,String instructions,String type,int count,BigDecimal score,String prompt){}
}
