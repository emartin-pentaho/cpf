package pt.webdetails.cpf;

import pt.webdetails.cpf.repository.api.IRWAccess;
import pt.webdetails.cpf.repository.api.IReadAccess;
import pt.webdetails.cpf.repository.api.IContentAccessFactory;
import pt.webdetails.cpf.repository.api.IUserContentAccess;
import pt.webdetails.cpf.repository.pentaho.PentahoLegacyUserContentAccess;
import pt.webdetails.cpf.repository.pentaho.PluginLegacySolutionResourceAccess;

public class PentahoPluginEnvironment extends PentahoBasePluginEnvironment implements IContentAccessFactory {

  public static PentahoPluginEnvironment getInstance() {
    return new PentahoPluginEnvironment();
  }

  @Override
  public IUserContentAccess getUserContentAccess(String basePath) {
    return new PentahoLegacyUserContentAccess(basePath, null);
  }

  @Override
  public IReadAccess getPluginRepositoryReader(String basePath) {
    return new PluginLegacySolutionResourceAccess(basePath);
  }

  @Override
  public IRWAccess getPluginRepositoryWriter(String basePath) {
    return new PluginLegacySolutionResourceAccess(basePath);
  }
}