#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include "llama.h"

struct LLHandle {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_sampler * smpl = nullptr;
    llama_token eos = 0;
};

static LLHandle * h( jlong p ) { return reinterpret_cast<LLHandle *>( p ); }

static std::string jstr( JNIEnv * env, jstring s ) {
    if ( !s ) return std::string();
    const char * c = env->GetStringUTFChars( s, nullptr );
    std::string r( c ? c : "" );
    if ( c ) env->ReleaseStringUTFChars( s, c );
    return r;
}

static void reportError( JNIEnv * env, jobject cb, const char * msg ) {
    if ( !cb ) return;
    jclass cls = env->GetObjectClass( cb );
    jmethodID mid = env->GetMethodID( cls, "onError", "(Ljava/lang/String;)V" );
    if ( mid ) {
        jstring m = env->NewStringUTF( msg );
        env->CallVoidMethod( cb, mid, m );
        env->DeleteLocalRef( m );
    }
    env->DeleteLocalRef( cls );
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_me_rerere_ai_local_LlamaNative_create(
    JNIEnv * env, jobject, jstring path, jint nCtx, jint threads ) {
    std::string modelPath = jstr( env, path );
    if ( modelPath.empty() ) return 0;
    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.use_mmap = true;
    mparams.use_mlock = false;

    llama_model * model = llama_load_model_from_file( modelPath.c_str(), mparams );
    if ( !model ) return 0;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>( nCtx > 0 ? nCtx : 2048 );
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = threads > 0 ? threads : 4;
    cparams.n_threads_batch = threads > 0 ? threads : 4;

    llama_context * ctx = llama_new_context_with_model( model, cparams );
    if ( !ctx ) {
        llama_free_model( model );
        return 0;
    }

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init( sparams );
    llama_sampler_chain_add( smpl, llama_sampler_init_temp( 0.8f ) );
    llama_sampler_chain_add( smpl, llama_sampler_init_top_k( 40 ) );
    llama_sampler_chain_add( smpl, llama_sampler_init_top_p( 0.9f, 1 ) );
    llama_sampler_chain_add( smpl, llama_sampler_init_dist( 0 ) );

    LLHandle * lh = new LLHandle();
    lh->model = model;
    lh->ctx = ctx;
    lh->smpl = smpl;
    lh->eos = llama_token_eos( model );
    return reinterpret_cast<jlong>( lh );
}

JNIEXPORT void JNICALL
Java_me_rerere_ai_local_LlamaNative_free(
    JNIEnv *, jobject, jlong handle ) {
    LLHandle * lh = h( handle );
    if ( !lh ) return;
    if ( lh->smpl ) llama_sampler_free( lh->smpl );
    if ( lh->ctx ) llama_free( lh->ctx );
    if ( lh->model ) llama_free_model( lh->model );
    delete lh;
}

JNIEXPORT jstring JNICALL
Java_me_rerere_ai_local_LlamaNative_stream(
    JNIEnv * env, jobject, jlong handle,
    jobjectArray roles, jobjectArray contents,
    jint maxTokens, jfloat temperature, jfloat topP,
    jobject cb ) {
    LLHandle * lh = h( handle );
    if ( !lh || !lh->model || !lh->ctx ) {
        reportError( env, cb, "模型句柄无效" );
        return env->NewStringUTF( "" );
    }

    jsize nRoles = roles ? env->GetArrayLength( roles ) : 0;
    jsize nContents = contents ? env->GetArrayLength( contents ) : 0;
    jsize nMsg = nRoles < nContents ? nRoles : nContents;
    if ( nMsg <= 0 ) return env->NewStringUTF( "" );

    std::vector<std::string> roleStrs;
    std::vector<std::string> contentStrs;
    roleStrs.reserve( nMsg );
    contentStrs.reserve( nMsg );
    for ( jsize i = 0; i < nMsg; i++ ) {
        auto jr = (jstring) env->GetObjectArrayElement( roles, i );
        auto jc = (jstring) env->GetObjectArrayElement( contents, i );
        roleStrs.push_back( jstr( env, jr ) );
        contentStrs.push_back( jstr( env, jc ) );
        env->DeleteLocalRef( jr );
        env->DeleteLocalRef( jc );
    }

    std::vector<llama_chat_message> chat( nMsg );
    for ( jsize i = 0; i < nMsg; i++ ) {
        chat[i].role = roleStrs[i].c_str();
        chat[i].content = contentStrs[i].c_str();
    }

    std::string prompt;
    int32_t len = llama_chat_apply_template( lh->model, nullptr, chat.data(), nMsg, true, nullptr, 0 );
    if ( len < 0 ) {
        // 模型元数据缺少 chat_template 时回退到手工通用模板，保证任何 GGUF 都能对话
        for ( jsize i = 0; i < nMsg; i++ ) {
            std::string role = roleStrs[i];
            if ( role == "system" ) {
                prompt += "System: " + contentStrs[i] + "\n\n";
            } else if ( role == "user" ) {
                prompt += "User: " + contentStrs[i] + "\n\n";
            } else if ( role == "assistant" ) {
                prompt += "Assistant: " + contentStrs[i] + "\n\n";
            }
        }
        prompt += "Assistant:";
    } else {
        prompt.resize( static_cast<size_t>( len ) + 1 );
        llama_chat_apply_template( lh->model, nullptr, chat.data(), nMsg, true, &prompt[0], len + 1 );
        if ( !prompt.empty() && prompt.back() == '\0' ) prompt.pop_back();
    }

    int32_t nTok = llama_tokenize( lh->model, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, false );
    if ( nTok <= 0 ) return env->NewStringUTF( "" );
    std::vector<llama_token> tokens( static_cast<size_t>( nTok ) );
    llama_tokenize( lh->model, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), nTok, true, false );

    llama_batch batch = llama_batch_init( static_cast<int32_t>( tokens.size() ), 0, 1 );
    for ( size_t i = 0; i < tokens.size(); i++ ) {
        batch.token[i] = tokens[i];
        batch.pos[i] = (llama_pos) i;      // 必须设置位置，否则 decode 失败
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.n_tokens++;
    }
    if ( llama_decode( lh->ctx, batch ) != 0 ) {
        llama_batch_free( batch );
        return env->NewStringUTF( "" );
    }
    llama_batch_free( batch );

    jclass cbCls = cb ? env->GetObjectClass( cb ) : nullptr;
    jmethodID onToken = cbCls ? env->GetMethodID( cbCls, "onToken", "(Ljava/lang/String;)V" ) : nullptr;
    jmethodID onError = cbCls ? env->GetMethodID( cbCls, "onError", "(Ljava/lang/String;)V" ) : nullptr;

    std::string out;
    int32_t limit = maxTokens > 0 ? maxTokens : 512;
    int32_t nOut = (int32_t) tokens.size();
    char piece[128];
    for ( int32_t i = 0; i < limit; i++ ) {
        llama_token id = llama_sampler_sample( lh->smpl, lh->ctx, -1 );
        if ( id == lh->eos ) break;

        int32_t plen = llama_token_to_piece( lh->model, id, piece, sizeof( piece ), 0, false );
        if ( plen < 0 ) continue;
        std::string text( piece, static_cast<size_t>( plen ) );
        out += text;
        if ( cb && onToken ) {
            jstring js = env->NewStringUTF( text.c_str() );
            env->CallVoidMethod( cb, onToken, js );
            env->DeleteLocalRef( js );
        }

        llama_batch sb = llama_batch_init( 1, 0, 1 );
        sb.token[0] = id;
        sb.pos[0] = (llama_pos) nOut;      // 续写位置 = 输入长度 + 已生成数
        sb.n_seq_id[0] = 1;
        sb.seq_id[0][0] = 0;
        sb.n_tokens = 1;
        int rc = llama_decode( lh->ctx, sb );
        llama_batch_free( sb );
        if ( rc != 0 ) break;
        nOut++;
    }

    if ( cbCls ) env->DeleteLocalRef( cbCls );
    return env->NewStringUTF( out.c_str() );
}

}
