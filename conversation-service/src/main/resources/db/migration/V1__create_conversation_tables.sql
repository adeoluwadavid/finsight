CREATE TABLE IF NOT EXISTS conversations.conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    title VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
    );

CREATE TABLE IF NOT EXISTS conversations.messages (
                                                      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversations.conversations(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    chart_spec JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
    );

CREATE INDEX idx_conversations_user_id ON conversations.conversations(user_id);
CREATE INDEX idx_messages_conversation_id ON conversations.messages(conversation_id);
CREATE INDEX idx_messages_created_at ON conversations.messages(created_at);