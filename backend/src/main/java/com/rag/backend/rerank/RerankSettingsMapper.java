package com.rag.backend.rerank;

import org.apache.ibatis.annotations.*;

@Mapper
public interface RerankSettingsMapper {
    @Select("SELECT * FROM rerank_runtime_settings WHERE id = 1")
    RerankSettings selectCurrent();

    @Insert("""
            INSERT INTO rerank_runtime_settings (id, provider, base_url, model, api_key, fail_open)
            VALUES (1, #{provider}, #{baseUrl}, #{model}, #{apiKey}, #{failOpen})
            """)
    int insert(RerankSettings settings);

    @Update("""
            UPDATE rerank_runtime_settings
            SET provider=#{provider}, base_url=#{baseUrl}, model=#{model}, api_key=#{apiKey},
                fail_open=#{failOpen}, updated_at=CURRENT_TIMESTAMP
            WHERE id=1
            """)
    int update(RerankSettings settings);

    default int upsert(RerankSettings settings) {
        return selectCurrent() == null ? insert(settings) : update(settings);
    }
}
