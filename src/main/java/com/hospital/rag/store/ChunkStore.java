package com.hospital.rag.store;

import com.hospital.rag.model.Chunk;
import com.hospital.rag.model.UserContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 片段仓库（内存实现）。
 *
 * <p>负责保存全部切分后的片段，并提供基于用户上下文的权限过滤视图。
 * 生产环境可替换为关系库 / ES 实现。</p>
 */
@Component
public class ChunkStore {

    private final Map<String, Chunk> chunks = new ConcurrentHashMap<>();

    /** 保存单个片段。 */
    public void save(Chunk chunk) {
        if (chunk != null && chunk.getId() != null) {
            chunks.put(chunk.getId(), chunk);
        }
    }

    /** 批量保存片段。 */
    public void saveAll(Collection<Chunk> toSave) {
        if (toSave != null) {
            toSave.forEach(this::save);
        }
    }

    /** 按 id 查询片段。 */
    public Optional<Chunk> findById(String id) {
        return Optional.ofNullable(chunks.get(id));
    }

    /** 返回全部片段。 */
    public List<Chunk> findAll() {
        return new ArrayList<>(chunks.values());
    }

    /** 返回某用户有权访问的全部片段。 */
    public List<Chunk> findAccessible(UserContext user) {
        List<Chunk> result = new ArrayList<>();
        for (Chunk chunk : chunks.values()) {
            if (chunk.isAccessible(user)) {
                result.add(chunk);
            }
        }
        return result;
    }

    /** 已存储片段总数。 */
    public int size() {
        return chunks.size();
    }

    /** 清空仓库。 */
    public void clear() {
        chunks.clear();
    }
}
