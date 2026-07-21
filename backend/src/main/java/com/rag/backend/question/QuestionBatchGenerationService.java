package com.rag.backend.question;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.agent.llm.ChatClient;
import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.common.BizException;
import com.rag.backend.practice.PracticeMapper;
import com.rag.backend.question.model.Question;
import com.rag.backend.question.model.QuestionBatch;
import com.rag.backend.question.model.QuestionBatchDetail;
import com.rag.backend.question.model.QuestionGenerationRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class QuestionBatchGenerationService {
    private static final Set<String> TYPES = Set.of(
            Question.TYPE_SINGLE_CHOICE, Question.TYPE_MULTI_CHOICE,
            Question.TYPE_TRUE_FALSE, Question.TYPE_SHORT_ANSWER, Question.TYPE_FILL_BLANK,
            Question.TYPE_COMPOSITION, Question.TYPE_CLASSICAL_CHINESE_READING,
            Question.TYPE_POETRY_APPRECIATION, Question.TYPE_MODERN_READING,
            Question.TYPE_TRANSLATION, Question.TYPE_SENTENCE_BREAK,
            Question.TYPE_EXPLANATION, Question.TYPE_LANGUAGE_BASIC, "mixed"
    );
    private static final Set<String> DIFFICULTIES = Set.of(
            Question.DIFF_EASY, Question.DIFF_MEDIUM, Question.DIFF_HARD, "mixed"
    );

    private final QuestionChunkMapper chunkMapper;
    private final QuestionBatchMapper batchMapper;
    private final QuestionMapper questionMapper;
    private final QuestionService questionService;
    private final PracticeMapper practiceMapper;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public QuestionBatchGenerationService(QuestionChunkMapper chunkMapper,
                                          QuestionBatchMapper batchMapper,
                                          QuestionMapper questionMapper,
                                          QuestionService questionService,
                                          PracticeMapper practiceMapper,
                                          ChatClient chatClient,
                                          ObjectMapper objectMapper) {
        this.chunkMapper = chunkMapper;
        this.batchMapper = batchMapper;
        this.questionMapper = questionMapper;
        this.questionService = questionService;
        this.practiceMapper = practiceMapper;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public QuestionBatchDetail generate(QuestionGenerationRequest request) {
        ValidatedRequest input = validate(request);
        List<KnowledgeChunk> available = chunkMapper.selectForGeneration(input.courseId(), input.documentIds());
        if (available.isEmpty()) {
            throw new BizException(400, "所选范围内没有已解析片段，请先上传并解析文档");
        }

        Set<Long> usedChunkIds = new HashSet<>(batchMapper.selectUsedChunkIds(input.courseId()));
        int materialCount = Math.min(30, Math.max(6, input.count() * 2));
        List<KnowledgeChunk> selected = selectBalanced(available, usedChunkIds, materialCount);

        List<KnowledgeChunk> styleChunks = input.referenceRealQuestions() && !input.styleDocumentIds().isEmpty()
                ? selectBalanced(chunkMapper.selectForGeneration(input.courseId(), input.styleDocumentIds()), Set.of(), 12)
                : List.of();
        String previousStyle = input.referenceRealQuestions() && styleChunks.isEmpty()
                ? batchMapper.selectLatestStyleSummary(input.courseId())
                : null;

        String response = chatClient.call(buildPrompt(input, selected, styleChunks, previousStyle));
        ParsedOutput parsed = parseOutput(input, response, selected);
        if (parsed.questions().isEmpty()) {
            throw new BizException(500, "模型没有返回可保存的题目，请调整要求后重试");
        }

        QuestionBatch batch = new QuestionBatch();
        batch.setCourseId(input.courseId());
        batch.setTitle(resolveTitle(input));
        batch.setMode(input.mode());
        batch.setRequirement(input.requirement());
        batch.setQuestionCount(parsed.questions().size());
        batch.setQuestionType(input.type());
        batch.setDifficulty(input.difficulty());
        batch.setReferenceRealQuestions(input.referenceRealQuestions());
        batch.setStyleSummary(parsed.styleSummary());
        batchMapper.insert(batch);

        List<Long> batchDocumentIds = input.documentIds().isEmpty()
                ? selected.stream().map(KnowledgeChunk::getDocumentId).filter(Objects::nonNull).distinct().toList()
                : input.documentIds();
        batchDocumentIds.forEach(id -> batchMapper.insertDocument(batch.getId(), id));
        selected.stream().map(KnowledgeChunk::getId).filter(Objects::nonNull).distinct()
                .forEach(id -> batchMapper.insertChunk(batch.getId(), id));
        parsed.questions().forEach(question -> question.setBatchId(batch.getId()));
        List<Question> saved = questionService.batchSave(parsed.questions());

        return detail(batch, saved);
    }

    public List<QuestionBatchDetail> list(Long courseId) {
        if (courseId == null) {
            throw new BizException(400, "courseId 不能为空");
        }
        List<Question> questions = questionService.listByCourse(courseId, null, null);
        Map<Long, List<Question>> byBatch = new HashMap<>();
        for (Question question : questions) {
            if (question.getBatchId() != null) {
                byBatch.computeIfAbsent(question.getBatchId(), ignored -> new ArrayList<>()).add(question);
            }
        }
        return batchMapper.selectByCourseId(courseId).stream()
                .map(batch -> detail(batch, byBatch.getOrDefault(batch.getId(), List.of())))
                .toList();
    }

    @Transactional
    public void deleteBatch(Long batchId) {
        QuestionBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BizException(404, "出题批次不存在: " + batchId);
        }
        practiceMapper.deleteByQuestionBatchId(batchId);
        questionMapper.deleteByBatchId(batchId);
        batchMapper.deleteDocuments(batchId);
        batchMapper.deleteChunks(batchId);
        batchMapper.deleteById(batchId);
    }

    private QuestionBatchDetail detail(QuestionBatch batch, List<Question> questions) {
        QuestionBatchDetail detail = new QuestionBatchDetail();
        detail.setBatch(batch);
        detail.setQuestions(questions);
        detail.setDocumentIds(batchMapper.selectDocumentIds(batch.getId()));
        detail.setChunkIds(batchMapper.selectChunkIds(batch.getId()));
        return detail;
    }

    private ValidatedRequest validate(QuestionGenerationRequest request) {
        if (request == null || request.getCourseId() == null) {
            throw new BizException(400, "courseId 不能为空");
        }
        String mode = "exam".equalsIgnoreCase(request.getMode()) ? "exam" : "practice";
        int max = "exam".equals(mode) ? 60 : 20;
        int count = Math.max(1, Math.min(request.getCount() == null ? 3 : request.getCount(), max));
        List<String> requestedTypes = request.getQuestionTypes() == null ? List.of() : request.getQuestionTypes().stream()
                .map(this::normalizeType).filter(TYPES::contains).distinct().toList();
        String type = requestedTypes.isEmpty() ? normalizeChoice(request.getType(), TYPES, "mixed") : "mixed";
        if (requestedTypes.isEmpty() && !"mixed".equals(type)) requestedTypes = List.of(type);
        String subject = Question.SUBJECT_CHINESE.equalsIgnoreCase(request.getSubject())
                ? Question.SUBJECT_CHINESE : Question.SUBJECT_GENERAL;
        String difficulty = normalizeChoice(request.getDifficulty(), DIFFICULTIES, "medium");
        String requirement = StringUtils.hasText(request.getRequirement())
                ? request.getRequirement().trim()
                : "覆盖所选资料的核心知识点，题干、选项、答案和解析使用中文";
        return new ValidatedRequest(
                request.getCourseId(), count, type, requestedTypes, subject, difficulty, requirement, mode,
                trim(request.getTitle()), distinctIds(request.getDocumentIds()),
                Boolean.TRUE.equals(request.getReferenceRealQuestions()), distinctIds(request.getStyleDocumentIds())
        );
    }

    private String normalizeChoice(String value, Set<String> allowed, String fallback) {
        String normalized = trim(value).toLowerCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : fallback;
    }

    private List<Long> distinctIds(List<Long> ids) {
        if (ids == null) return List.of();
        return ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    private List<KnowledgeChunk> selectBalanced(List<KnowledgeChunk> source, Set<Long> usedIds, int limit) {
        if (source == null || source.isEmpty()) return List.of();
        List<KnowledgeChunk> unused = source.stream().filter(item -> !usedIds.contains(item.getId())).toList();
        List<KnowledgeChunk> pool = unused.size() >= Math.min(limit, source.size()) ? unused : source;
        Map<Long, List<KnowledgeChunk>> groups = new HashMap<>();
        for (KnowledgeChunk chunk : pool) {
            groups.computeIfAbsent(chunk.getDocumentId(), ignored -> new ArrayList<>()).add(chunk);
        }
        groups.values().forEach(list -> Collections.shuffle(list, random));
        List<Long> documentOrder = new ArrayList<>(groups.keySet());
        Collections.shuffle(documentOrder, random);
        List<KnowledgeChunk> result = new ArrayList<>();
        int cursor = 0;
        while (result.size() < limit && !documentOrder.isEmpty()) {
            Long documentId = documentOrder.get(cursor % documentOrder.size());
            List<KnowledgeChunk> group = groups.get(documentId);
            if (group.isEmpty()) {
                documentOrder.remove(documentId);
                cursor = 0;
                continue;
            }
            result.add(group.remove(group.size() - 1));
            cursor++;
        }
        Collections.shuffle(result, random);
        return result;
    }

    private String buildPrompt(ValidatedRequest input,
                               List<KnowledgeChunk> chunks,
                               List<KnowledgeChunk> styleChunks,
                               String previousStyle) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是严谨的课程命题专家。请严格基于给定资料生成题目，不得编造资料外事实。\n")
                .append("模式：").append("exam".equals(input.mode()) ? "完整套卷" : "练习题批次").append('\n')
                .append("数量：").append(input.count()).append("；题型：").append(input.type())
                .append(input.questionTypes().isEmpty() ? "" : "（限定：" + String.join(",", input.questionTypes()) + "）")
                .append("；学科：").append(input.subject())
                .append("；难度：").append(input.difficulty()).append('\n')
                .append("补充要求：").append(input.requirement()).append('\n')
                .append("随机性要求：尽量覆盖不同片段与知识点，相邻题目不要反复考察同一概念。\n");
        if ("exam".equals(input.mode())) {
            prompt.append("套卷要求：知识点分布均衡，混合题型时应包含单选、多选、判断和简答，并形成由易到难的合理梯度。\n");
        }
        if (input.referenceRealQuestions()) {
            prompt.append("真实命题风格：学习真实题目的措辞、考点组织、干扰项和难度结构，但不得照抄题目。\n");
            if (StringUtils.hasText(previousStyle)) {
                prompt.append("可复用的历史命题风格画像：").append(previousStyle).append('\n');
            }
        }
        if (Question.SUBJECT_CHINESE.equals(input.subject())) {
            prompt.append("语文结构规则：作文题 questionData 必须含 requirements；文言文阅读、古诗词鉴赏、现代文阅读的 questionData 必须含 material 和 subQuestions。")
                    .append("每个 subQuestion 必须含 key、type、stem、answer、explanation、maxScore、gradingStrategy；客观小题 gradingStrategy=rule，主观小题=manual。")
                    .append("作文、翻译等主观题不得设置唯一正确结论，answerSchema 应包含 referenceAnswer、scoringPoints 或 rubric。")
                    .append("原文、诗词和出处只可来自课程片段；资料无出处时 source 留空。\n");
            appendChineseSchemaExample(prompt, input.questionTypes());
        }
        prompt.append("仅输出 JSON 对象，不要 Markdown：{\"styleSummary\":\"参考真实题目时总结可复用命题风格，否则为空\",\"questions\":[")
                .append("{\"type\":\"题型\",\"subject\":\"general|chinese\",\"stem\":\"题干\",\"options\":[\"A. ...\"],")
                .append("\"answer\":\"A/AB/正确/文本\",\"explanation\":\"解析\",\"difficulty\":\"easy|medium|hard\",")
                .append("\"knowledgePoint\":\"知识点\",\"chapters\":[\"第1章\"],\"sourceChunkId\":1,\"questionData\":{},\"answerSchema\":{}}]}。")
                .append("chapters 必须列出该题涉及的全部章节；跨章节题同时写入多个章节，章节名称优先沿用资料标题。questions 必须恰好为指定数量。\n\n")
                .append("课程内容片段：\n");
        appendChunks(prompt, chunks);
        if (!styleChunks.isEmpty()) {
            prompt.append("\n真实题目风格参考片段（仅学习命题方式）：\n");
            appendChunks(prompt, styleChunks);
        }
        return prompt.toString();
    }

    private void appendChunks(StringBuilder prompt, List<KnowledgeChunk> chunks) {
        for (KnowledgeChunk chunk : chunks) {
            prompt.append("[chunkId=").append(chunk.getId())
                    .append(", documentId=").append(chunk.getDocumentId())
                    .append(", title=").append(trim(chunk.getTitle())).append("]\n")
                    .append(abbreviate(chunk.getContent(), 1800)).append("\n---\n");
        }
    }

    private ParsedOutput parseOutput(ValidatedRequest input, String raw, List<KnowledgeChunk> chunks) {
        JsonNode root;
        try {
            root = objectMapper.readTree(extractJson(raw));
        } catch (JsonProcessingException e) {
            throw new BizException(500, "AI 出题结果不是有效 JSON: " + e.getOriginalMessage());
        }
        JsonNode array = root.isArray() ? root : root.path("questions");
        if (!array.isArray()) {
            throw new BizException(500, "AI 出题结果缺少 questions 数组");
        }
        Set<Long> allowedChunkIds = new HashSet<>();
        chunks.stream().map(KnowledgeChunk::getId).filter(Objects::nonNull).forEach(allowedChunkIds::add);
        List<Question> questions = new ArrayList<>();
        int index = 0;
        for (JsonNode item : array) {
            if (questions.size() >= input.count()) break;
            Question question = toQuestion(input, item, chunks, allowedChunkIds, index++);
            if (question != null) questions.add(question);
        }
        String styleSummary = root.isObject() ? trim(root.path("styleSummary").asText("")) : "";
        return new ParsedOutput(styleSummary, questions);
    }

    private Question toQuestion(ValidatedRequest input,
                                JsonNode item,
                                List<KnowledgeChunk> chunks,
                                Set<Long> allowedChunkIds,
                                int index) {
        String stem = trim(item.path("stem").asText(item.path("question").asText("")));
        String answer = trim(item.path("answer").asText(item.path("referenceAnswer").asText(
                item.path("answerSchema").path("referenceAnswer").asText(""))));
        if (stem.isEmpty()) return null;
        String type = normalizeType(item.path("type").asText(""));
        if (!input.questionTypes().isEmpty() && !input.questionTypes().contains(type)) return null;
        if (!Question.TYPE_COMPOSITION.equals(type) && !isCompositeType(type) && answer.isEmpty()
                && !item.path("answerSchema").isObject()) return null;
        List<String> options = parseOptions(item.path("options"), type);
        if ((Question.TYPE_SINGLE_CHOICE.equals(type) || Question.TYPE_MULTI_CHOICE.equals(type)) && options.size() < 2) {
            return null;
        }
        Long sourceChunkId = item.path("sourceChunkId").canConvertToLong() ? item.path("sourceChunkId").asLong() : null;
        if (!allowedChunkIds.contains(sourceChunkId)) {
            sourceChunkId = chunks.get(index % chunks.size()).getId();
        }
        Question question = new Question();
        question.setCourseId(input.courseId());
        question.setSourceChunkId(sourceChunkId);
        question.setType(type);
        question.setStem(stem);
        question.setOptions(options.isEmpty() ? null : writeJson(options));
        question.setAnswer(normalizeAnswer(answer, type));
        question.setExplanation(trim(item.path("explanation").asText("依据课程资料生成。")));
        question.setDifficulty(normalizeDifficulty(item.path("difficulty").asText("medium")));
        question.setKnowledgePoint(trim(item.path("knowledgePoint").asText("课程知识点")));
        Long resolvedSourceChunkId = sourceChunkId;
        KnowledgeChunk sourceChunk = chunks.stream()
                .filter(chunk -> Objects.equals(chunk.getId(), resolvedSourceChunkId))
                .findFirst()
                .orElse(null);
        question.setChapterTags(writeJson(parseChapterTags(item.path("chapters"), sourceChunk)));
        question.setSubject(Question.SUBJECT_CHINESE.equals(input.subject())
                || isChineseType(type) ? Question.SUBJECT_CHINESE : Question.SUBJECT_GENERAL);
        question.setGradingStrategy(defaultGradingStrategy(type));
        JsonNode questionData = normalizeQuestionData(item);
        if (isCompositeType(type) && (!questionData.path("material").isObject()
                || !questionData.path("material").path("text").isTextual()
                || questionData.path("material").path("text").asText().isBlank()
                || !questionData.path("subQuestions").isArray() || questionData.path("subQuestions").isEmpty())) return null;
        if (Question.TYPE_COMPOSITION.equals(type) && !questionData.path("requirements").isObject()) return null;
        question.setQuestionData(questionData.isEmpty() ? null : writeJson(questionData));
        question.setAnswerSchema(item.path("answerSchema").isObject() && !item.path("answerSchema").isEmpty()
                ? writeJson(item.path("answerSchema")) : null);
        return question;
    }

    private List<String> parseChapterTags(JsonNode node, KnowledgeChunk sourceChunk) {
        List<String> chapters = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> {
                String chapter = trim(item.asText(""));
                if (!chapter.isEmpty() && !chapters.contains(chapter) && chapters.size() < 12) {
                    chapters.add(chapter);
                }
            });
        }
        if (chapters.isEmpty() && sourceChunk != null) {
            chapters.addAll(ChapterTagExtractor.extract(sourceChunk.getTitle(), sourceChunk.getContent()));
        }
        return chapters;
    }

    private List<String> parseOptions(JsonNode node, String type) {
        if (Question.TYPE_SHORT_ANSWER.equals(type)) return List.of();
        if (Question.TYPE_TRUE_FALSE.equals(type) && !node.isArray()) return List.of("正确", "错误");
        List<String> options = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> {
                String value = trim(item.asText(""));
                if (!value.isEmpty()) options.add(value);
            });
        }
        return options;
    }

    private String normalizeType(String value) {
        String text = trim(value).toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (text) {
            case "multi_choice", "multiple_choice", "多选", "多选题" -> Question.TYPE_MULTI_CHOICE;
            case "true_false", "truefalse", "判断", "判断题" -> Question.TYPE_TRUE_FALSE;
            case "short_answer", "short", "简答", "简答题" -> Question.TYPE_SHORT_ANSWER;
            case "fill_blank", "填空", "填空题", "默写", "名句默写", "名篇名句默写" -> Question.TYPE_FILL_BLANK;
            case "composition", "作文", "作文题" -> Question.TYPE_COMPOSITION;
            case "classical_chinese_reading", "文言文", "文言文阅读", "文言文阅读题" -> Question.TYPE_CLASSICAL_CHINESE_READING;
            case "poetry_appreciation", "诗歌鉴赏", "古诗鉴赏", "古诗词鉴赏", "古代诗歌阅读" -> Question.TYPE_POETRY_APPRECIATION;
            case "modern_reading", "现代文", "现代文阅读", "现代文阅读题", "文学类文本阅读", "论述类文本阅读" -> Question.TYPE_MODERN_READING;
            case "translation", "翻译" -> Question.TYPE_TRANSLATION;
            case "sentence_break", "断句" -> Question.TYPE_SENTENCE_BREAK;
            case "explanation", "字词解释" -> Question.TYPE_EXPLANATION;
            case "language_basic", "语言基础", "语言基础题", "语言文字运用", "语言文字运用题" -> Question.TYPE_LANGUAGE_BASIC;
            default -> Question.TYPE_SINGLE_CHOICE;
        };
    }

    private String normalizeDifficulty(String value) {
        String text = trim(value).toLowerCase(Locale.ROOT);
        if (Set.of("easy", "简单", "容易").contains(text)) return Question.DIFF_EASY;
        if (Set.of("hard", "困难", "难").contains(text)) return Question.DIFF_HARD;
        return Question.DIFF_MEDIUM;
    }

    private String normalizeAnswer(String value, String type) {
        String answer = trim(value);
        if (Question.TYPE_SINGLE_CHOICE.equals(type) || Question.TYPE_MULTI_CHOICE.equals(type)) {
            String letters = answer.toUpperCase(Locale.ROOT).replaceAll("[^A-D]", "");
            if (!letters.isEmpty()) return Question.TYPE_SINGLE_CHOICE.equals(type) ? letters.substring(0, 1) : letters;
        }
        if (Question.TYPE_TRUE_FALSE.equals(type)) {
            return Set.of("true", "正确", "对", "是").contains(answer.toLowerCase(Locale.ROOT)) ? "正确" : "错误";
        }
        return answer;
    }

    private String extractJson(String raw) {
        if (!StringUtils.hasText(raw)) throw new BizException(500, "AI 出题结果为空");
        String text = raw.trim();
        int firstObject = text.indexOf('{');
        int lastObject = text.lastIndexOf('}');
        if (firstObject >= 0 && lastObject > firstObject) return text.substring(firstObject, lastObject + 1);
        int firstArray = text.indexOf('[');
        int lastArray = text.lastIndexOf(']');
        if (firstArray >= 0 && lastArray > firstArray) return text.substring(firstArray, lastArray + 1);
        return text;
    }

    private String writeJson(List<String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BizException(500, "题目选项序列化失败");
        }
    }

    private String writeJson(JsonNode value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new BizException(500, "题目结构序列化失败"); }
    }

    private JsonNode normalizeSubQuestions(JsonNode source) {
        var array = objectMapper.createArrayNode();
        int index = 0;
        for (JsonNode item : source) {
            if (!item.isObject()) continue;
            var copy = (com.fasterxml.jackson.databind.node.ObjectNode) item.deepCopy();
            if (!copy.hasNonNull("stem") && copy.hasNonNull("question")) copy.set("stem", copy.get("question"));
            if (!copy.hasNonNull("answer") && copy.hasNonNull("referenceAnswer")) copy.set("answer", copy.get("referenceAnswer"));
            if (!copy.hasNonNull("key") || copy.path("key").asText().isBlank()) copy.put("key", "q" + (index + 1));
            String type = normalizeType(copy.path("type").asText("short_answer"));
            copy.put("type", type);
            if (!copy.hasNonNull("gradingStrategy")) copy.put("gradingStrategy", defaultGradingStrategy(type));
            if (!copy.hasNonNull("maxScore")) copy.put("maxScore", 1);
            array.add(copy);
            index++;
        }
        return array;
    }

    private JsonNode normalizeQuestionData(JsonNode item) {
        var data = objectMapper.createObjectNode();
        if (item.path("questionData").isObject()) data.setAll((com.fasterxml.jackson.databind.node.ObjectNode) item.path("questionData").deepCopy());
        JsonNode material = firstPresent(data.get("material"), item.get("material"), data.get("passage"), item.get("passage"), data.get("text"));
        if (material != null) {
            if (material.isTextual()) { var wrapped=objectMapper.createObjectNode(); wrapped.put("text", material.asText()); material=wrapped; }
            if (material.isObject() && !material.has("text") && material.has("content")) ((com.fasterxml.jackson.databind.node.ObjectNode)material).set("text",material.get("content"));
            if (material.isObject()) data.set("material",material);
        }
        JsonNode subs = firstPresent(data.get("subQuestions"), item.get("subQuestions"), data.get("questions"), item.get("questions"), data.get("items"));
        if (subs != null && subs.isArray()) data.set("subQuestions",normalizeSubQuestions(subs));
        JsonNode requirements = firstPresent(data.get("requirements"),item.get("requirements"),data.get("requirement"));
        if (requirements != null) {
            if (requirements.isTextual()) { var wrapped=objectMapper.createObjectNode(); wrapped.put("description",requirements.asText()); requirements=wrapped; }
            if (requirements.isObject()) data.set("requirements",requirements);
        }
        return data;
    }

    private JsonNode firstPresent(JsonNode... nodes) {
        for (JsonNode node:nodes) if (node!=null && !node.isNull() && !node.isMissingNode()) return node;
        return null;
    }

    private void appendChineseSchemaExample(StringBuilder prompt, List<String> types) {
        if (types.stream().anyMatch(this::isCompositeType)) {
            prompt.append("复合阅读题必须严格使用此结构（不可改字段名）：")
                    .append("{\"type\":\"modern_reading\",\"stem\":\"阅读下面材料，完成各题\",\"questionData\":")
                    .append("{\"material\":{\"text\":\"完整原文\",\"author\":\"\",\"source\":\"\"},\"subQuestions\":[")
                    .append("{\"key\":\"q1\",\"type\":\"short_answer\",\"stem\":\"小题题干\",\"answer\":\"参考答案\",\"explanation\":\"解析\",\"maxScore\":5,\"gradingStrategy\":\"manual\"}]},")
                    .append("\"answerSchema\":{\"referenceAnswer\":\"见各小题\"}}。material 必须是对象，subQuestions 必须是非空数组。\n");
        }
        if (types.contains(Question.TYPE_COMPOSITION)) {
            prompt.append("作文题 questionData 严格使用：{\"requirements\":{\"genre\":\"议论文\",\"wordCount\":800,\"mustInclude\":[]}}，answerSchema 必须含 rubric。\n");
        }
        if (types.contains(Question.TYPE_LANGUAGE_BASIC)) {
            prompt.append("语言文字运用的每道题 type 必须精确为 language_basic，并提供非空 stem、answer 和 explanation。\n");
        }
    }

    private boolean isChineseType(String type) {
        return Set.of(Question.TYPE_FILL_BLANK, Question.TYPE_COMPOSITION,
                Question.TYPE_CLASSICAL_CHINESE_READING, Question.TYPE_POETRY_APPRECIATION,
                Question.TYPE_MODERN_READING, Question.TYPE_TRANSLATION, Question.TYPE_SENTENCE_BREAK,
                Question.TYPE_EXPLANATION, Question.TYPE_LANGUAGE_BASIC).contains(type);
    }

    private boolean isCompositeType(String type) {
        return Set.of(Question.TYPE_CLASSICAL_CHINESE_READING,
                Question.TYPE_POETRY_APPRECIATION, Question.TYPE_MODERN_READING).contains(type);
    }

    private String defaultGradingStrategy(String type) {
        if (Set.of(Question.TYPE_CLASSICAL_CHINESE_READING, Question.TYPE_POETRY_APPRECIATION,
                Question.TYPE_MODERN_READING).contains(type)) return Question.GRADING_MIXED;
        if (Set.of(Question.TYPE_COMPOSITION, Question.TYPE_TRANSLATION,
                Question.TYPE_SENTENCE_BREAK, Question.TYPE_EXPLANATION).contains(type)) return Question.GRADING_MANUAL;
        if (Question.TYPE_SHORT_ANSWER.equals(type)) return Question.GRADING_AI;
        return Question.GRADING_RULE;
    }

    private String resolveTitle(ValidatedRequest input) {
        if (!input.title().isEmpty()) return input.title();
        String prefix = "exam".equals(input.mode()) ? "套卷" : "出题批次";
        return prefix + " · " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
    }

    private String trim(String value) { return value == null ? "" : value.trim(); }
    private String abbreviate(String value, int max) {
        String text = value == null ? "" : value;
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private record ValidatedRequest(Long courseId, int count, String type, List<String> questionTypes,
                                    String subject, String difficulty,
                                    String requirement, String mode, String title, List<Long> documentIds,
                                    boolean referenceRealQuestions, List<Long> styleDocumentIds) {}
    private record ParsedOutput(String styleSummary, List<Question> questions) {}
}
